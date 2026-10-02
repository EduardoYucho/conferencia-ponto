package br.com.conferenciaponto.infrastructure.google;

import br.com.conferenciaponto.application.planilha.PlanilhaRemotaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Chave da conta de serviço do Google (o arquivo JSON criado no Google Cloud). Fica num arquivo no computador
 * do servidor, fora do banco de dados e do repositório; a API do sistema só devolve o e-mail e o projeto da
 * conta, nunca a chave.
 */
@Component
public class CredencialGoogle {

    /** @param idChave identificador da chave no Google (vai no cabeçalho do JWT) */
    public record Chave(String email, String projeto, String idChave, PrivateKey chavePrivada) {
    }

    private static final Logger log = LoggerFactory.getLogger(CredencialGoogle.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final Path arquivo;
    private final ObjectMapper json;
    private volatile Chave chave;
    private volatile boolean carregada;

    public CredencialGoogle(GoogleProperties properties, ObjectMapper json) {
        this.arquivo = properties.arquivoCredencial();
        this.json = json;
    }

    /** A chave configurada (lida do arquivo na primeira vez). */
    public Optional<Chave> chave() {
        if (!carregada) {
            carregar();
        }
        return Optional.ofNullable(chave);
    }

    private synchronized void carregar() {
        if (carregada) {
            return;
        }
        if (Files.isRegularFile(arquivo)) {
            try {
                chave = ler(Files.readString(arquivo, StandardCharsets.UTF_8));
                log.info("Conta de serviço do Google: {} (projeto {})", chave.email(), chave.projeto());
            } catch (IOException | PlanilhaRemotaException e) {
                log.warn("Chave do Google em {} não pôde ser lida: {}", arquivo, e.getMessage());
            }
        }
        carregada = true;
    }

    /** Interpreta o JSON da chave sem gravar nada. */
    public Chave ler(String conteudo) {
        JsonNode no;
        try {
            no = json.readTree(conteudo == null ? "" : conteudo.strip());
        } catch (IOException e) {
            throw invalida("O arquivo não é um JSON. Envie o arquivo .json da chave baixado do Google Cloud.");
        }
        if (no == null || !no.isObject()) {
            throw invalida("O arquivo não é um JSON. Envie o arquivo .json da chave baixado do Google Cloud.");
        }
        if (!"service_account".equals(no.path("type").asText())) {
            throw invalida("Este JSON não é uma chave de conta de serviço (o campo \"type\" deveria ser "
                    + "\"service_account\"). Crie a chave em IAM e administrador > Contas de serviço > Chaves.");
        }
        String email = no.path("client_email").asText("");
        String pem = no.path("private_key").asText("");
        if (email.isBlank() || pem.isBlank()) {
            throw invalida("A chave está incompleta (faltam \"client_email\" ou \"private_key\").");
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
            PrivateKey privada = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            return new Chave(email.strip(), no.path("project_id").asText(""), no.path("private_key_id").asText(""), privada);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw invalida("A chave privada do arquivo não pôde ser lida. Baixe uma chave nova (formato JSON).");
        }
    }

    /** Grava a chave (já conferida) no arquivo de credencial. */
    public synchronized Chave gravar(String conteudo) {
        Chave nova = ler(conteudo);
        try {
            Files.createDirectories(arquivo.getParent());
            Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
            Files.writeString(temporario, conteudo.strip(), StandardCharsets.UTF_8);
            somenteDoDono(temporario);
            Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new PlanilhaRemotaException("GOOGLE_CHAVE_NAO_GRAVADA",
                    "Não foi possível gravar a chave em %s: %s".formatted(arquivo, e.getMessage()), false, e);
        }
        chave = nova;
        carregada = true;
        log.info("Conta de serviço do Google configurada: {} (projeto {})", nova.email(), nova.projeto());
        return nova;
    }

    public synchronized void remover() {
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException e) {
            log.warn("Não foi possível apagar {}: {}", arquivo, e.getMessage());
        }
        chave = null;
        carregada = true;
        log.info("Conta de serviço do Google removida");
    }

    /**
     * JWT assinado com a chave, trocado por um token de acesso no Google.
     *
     * @param audiencia o endereço do serviço de tokens
     */
    public String assercao(Chave c, String escopo, String audiencia, Instant agora) {
        try {
            Map<String, Object> cabecalho = new LinkedHashMap<>();
            cabecalho.put("alg", "RS256");
            cabecalho.put("typ", "JWT");
            if (!c.idChave().isBlank()) {
                cabecalho.put("kid", c.idChave());
            }
            long emitido = agora.getEpochSecond() - 30; // folga para relógios um pouco adiantados
            Map<String, Object> dados = new LinkedHashMap<>();
            dados.put("iss", c.email());
            dados.put("scope", escopo);
            dados.put("aud", audiencia);
            dados.put("iat", emitido);
            dados.put("exp", emitido + 3600);
            String conteudo = B64.encodeToString(json.writeValueAsBytes(cabecalho)) + "."
                    + B64.encodeToString(json.writeValueAsBytes(dados));
            Signature assinatura = Signature.getInstance("SHA256withRSA");
            assinatura.initSign(c.chavePrivada());
            assinatura.update(conteudo.getBytes(StandardCharsets.US_ASCII));
            return conteudo + "." + B64.encodeToString(assinatura.sign());
        } catch (IOException | GeneralSecurityException e) {
            throw new PlanilhaRemotaException("GOOGLE_CHAVE_INVALIDA", "Não foi possível assinar com a chave do Google: "
                    + e.getMessage(), false, e);
        }
    }

    private static void somenteDoDono(Path arquivo) {
        try {
            Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: a pasta do perfil do usuário já é restrita a ele
        }
    }

    private static PlanilhaRemotaException invalida(String mensagem) {
        return new PlanilhaRemotaException("GOOGLE_CHAVE_INVALIDA", mensagem, false);
    }
}
