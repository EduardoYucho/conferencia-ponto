package br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto;

import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveCifrada;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.Cofre;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.CofreException;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * AES-256-GCM com a chave mestra guardada num arquivo fora do banco ({@code atendimento.chave-mestra.arquivo}).
 *
 * <ul>
 *   <li>A chave mestra é criada na primeira vez que for preciso (256 bits aleatórios), com leitura só para o
 *       usuário do serviço. Um arquivo que já existe nunca é sobrescrito: se estiver estragado, o cofre para e
 *       avisa (restaurar do backup).</li>
 *   <li>Cada cifra usa um vetor inicial aleatório de 12 bytes e o id do dono como dado associado: a chave
 *       cifrada de uma pessoa não é decifrada para outra, e qualquer alteração é detectada.</li>
 *   <li>Arquivo apagado ou trocado: uma chave mestra nova é criada e as chaves antigas deixam de abrir
 *       ({@code CHAVE_MESTRA_TROCADA}: cada um cadastra a sua de novo).</li>
 * </ul>
 */
@Component
public class CofreAesGcm implements Cofre {

    private static final Logger log = LoggerFactory.getLogger(CofreAesGcm.class);

    static final int VERSAO = 1;
    private static final String PREFIXO = "v1:";
    private static final String ALGORITMO = "AES/GCM/NoPadding";
    private static final int BYTES_DO_VETOR = 12;
    private static final int BITS_DA_ETIQUETA = 128;

    private final Path arquivo;
    private final SecureRandom aleatorio = new SecureRandom();
    private volatile SecretKey chaveMestra;

    public CofreAesGcm(ChaveMestraProperties properties) {
        this.arquivo = properties.arquivo().toAbsolutePath().normalize();
    }

    @Override
    public ChaveCifrada cifrar(String texto, UUID dono) {
        byte[] vetor = new byte[BYTES_DO_VETOR];
        aleatorio.nextBytes(vetor);
        try {
            Cipher cifra = Cipher.getInstance(ALGORITMO);
            cifra.init(Cipher.ENCRYPT_MODE, chaveMestra(), new GCMParameterSpec(BITS_DA_ETIQUETA, vetor));
            cifra.updateAAD(bytes(dono));
            return new ChaveCifrada(cifra.doFinal(texto.getBytes(StandardCharsets.UTF_8)), vetor, VERSAO);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM indisponível nesta JVM", e);
        }
    }

    @Override
    public String decifrar(ChaveCifrada cifrada, UUID dono) {
        if (cifrada.versaoChaveMestra() != VERSAO) {
            throw CofreException.chaveMestraTrocada();
        }
        try {
            Cipher cifra = Cipher.getInstance(ALGORITMO);
            cifra.init(Cipher.DECRYPT_MODE, chaveMestra(), new GCMParameterSpec(BITS_DA_ETIQUETA, cifrada.vetorInicial()));
            cifra.updateAAD(bytes(dono));
            return new String(cifra.doFinal(cifrada.cifrada()), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            // chave mestra diferente da que cifrou (ou dono diferente, ou dado alterado no banco)
            throw CofreException.chaveMestraTrocada();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM indisponível nesta JVM", e);
        }
    }

    private SecretKey chaveMestra() {
        SecretKey atual = chaveMestra;
        if (atual == null) {
            synchronized (this) {
                atual = chaveMestra;
                if (atual == null) {
                    atual = carregarOuCriar();
                    chaveMestra = atual;
                }
            }
        }
        return atual;
    }

    private SecretKey carregarOuCriar() {
        try {
            if (Files.exists(arquivo)) {
                return ler();
            }
            return criar();
        } catch (IOException e) {
            log.error("Chave mestra em {} indisponível: {}", arquivo, e.toString());
            throw new CofreException("CHAVE_MESTRA_INDISPONIVEL", "O servidor não conseguiu ler nem criar o arquivo "
                    + "da chave mestra (" + arquivo + "). Avise o administrador.");
        }
    }

    private SecretKey ler() throws IOException {
        // ISO-8859-1 lê qualquer byte: conteúdo estranho vira "ilegível", nunca erro de leitura
        String conteudo = new String(Files.readAllBytes(arquivo), StandardCharsets.ISO_8859_1).strip();
        byte[] bytes = null;
        if (conteudo.startsWith(PREFIXO)) {
            try {
                bytes = Base64.getDecoder().decode(conteudo.substring(PREFIXO.length()));
            } catch (IllegalArgumentException e) {
                bytes = null;
            }
        }
        if (bytes == null || bytes.length != 32) {
            log.error("Arquivo da chave mestra ilegível: {}", arquivo);
            throw new CofreException("CHAVE_MESTRA_ILEGIVEL", "O arquivo da chave mestra (" + arquivo + ") está "
                    + "estragado. Restaure-o do backup (ponto backup lista) ou, se não houver, apague-o: as chaves do "
                    + "Gemini terão de ser cadastradas de novo.");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private SecretKey criar() throws IOException {
        Path pasta = arquivo.getParent();
        Files.createDirectories(pasta);
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        Path temporario = Files.createTempFile(pasta, "chave-mestra", ".tmp");
        try {
            restringirAoDono(temporario);
            Files.writeString(temporario, PREFIXO + Base64.getEncoder().encodeToString(bytes) + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
            Files.move(temporario, arquivo);   // sem substituir: se outro processo criou antes, vale o dele
        } catch (FileAlreadyExistsException e) {
            return ler();
        } finally {
            Files.deleteIfExists(temporario);
        }
        log.warn("Chave mestra das chaves do Gemini criada em {} (fica fora do banco; o \"ponto backup\" copia o arquivo)",
                arquivo);
        return new SecretKeySpec(bytes, "AES");
    }

    /** Só o dono do arquivo (o usuário do serviço) lê e escreve. Se o sistema de arquivos não deixar, só avisa. */
    private static void restringirAoDono(Path caminho) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(caminho, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(caminho, AclFileAttributeView.class);
            if (acl != null) {
                AclEntry soODono = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
                acl.setAcl(List.of(soODono));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.warn("Não foi possível restringir o acesso ao arquivo da chave mestra: {}", e.toString());
        }
    }

    private static byte[] bytes(UUID dono) {
        return ByteBuffer.allocate(16).putLong(dono.getMostSignificantBits()).putLong(dono.getLeastSignificantBits())
                .array();
    }
}
