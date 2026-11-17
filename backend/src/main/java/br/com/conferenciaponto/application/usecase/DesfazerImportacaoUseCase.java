package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.TravaDosMesesConferidos;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.ComprovanteImportado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import br.com.conferenciaponto.domain.port.ArmazenamentoComprovantes;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import br.com.conferenciaponto.domain.port.ComprovantePontoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Um comprovante que entrou no ponto da pessoa errada (o PDF da colega caiu na pasta dela, por exemplo). O PDF não
 * diz de quem é (o nome está só na imagem e a assinatura é a da empresa), então quem corrige é gente:
 * <ul>
 *   <li><b>desfazer</b> (a própria pessoa): a batida que o PDF criou sai do dia (o dia some se ela era a única), o
 *       PDF vai para os desfeitos do armazenamento e fica registrado como DESFEITO para ela, com o motivo: a pasta
 *       dela não o importa de novo, e a pessoa certa pode importá-lo (pela pasta ou pela tela). Se a batida não veio
 *       do PDF (foi batida pelo botão e o PDF só a comprovava), só o PDF sai;</li>
 *   <li><b>mover</b> (quem configura o ponto da equipe): desfaz e importa o mesmo PDF no ponto da pessoa certa, numa
 *       gravação só. Se ele não puder entrar lá, nada muda.</li>
 * </ul>
 * O mês conferido da pessoa de quem o PDF sai fica travado, como qualquer mudança à mão. Tudo fica no histórico do
 * dia (origem DESFEITO).
 */
@Service
public class DesfazerImportacaoUseCase {

    private static final Logger log = LoggerFactory.getLogger(DesfazerImportacaoUseCase.class);
    static final int MOTIVO_MINIMO = 5;
    static final int MOTIVO_MAXIMO = 200;
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Sem exigir 1 minuto entre as batidas que ficam (elas já estavam no dia). */
    private static final RegistroJornada.RegrasAjuste SO_TIRAR = new RegistroJornada.RegrasAjuste(0, 1, false);

    private final ComprovanteArquivadoRepository arquivos;
    private final ComprovantePontoRepository comprovantes;
    private final RegistroJornadaRepository registros;
    private final AjusteJornadaRepository historico;
    private final ArmazenamentoComprovantes armazenamento;
    private final RegrasJornada regras;
    private final TravaDosMesesConferidos trava;
    private final ImportarComprovanteUseCase importar;
    private final UsuarioRepository usuarios;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public DesfazerImportacaoUseCase(ComprovanteArquivadoRepository arquivos, ComprovantePontoRepository comprovantes,
                                     RegistroJornadaRepository registros, AjusteJornadaRepository historico,
                                     ArmazenamentoComprovantes armazenamento, RegrasJornada regras,
                                     TravaDosMesesConferidos trava, ImportarComprovanteUseCase importar,
                                     UsuarioRepository usuarios, ApplicationEventPublisher eventos, Clock clock) {
        this.arquivos = arquivos;
        this.comprovantes = comprovantes;
        this.registros = registros;
        this.historico = historico;
        this.armazenamento = armazenamento;
        this.regras = regras;
        this.trava = trava;
        this.importar = importar;
        this.usuarios = usuarios;
        this.eventos = eventos;
        this.clock = clock;
    }

    /**
     * @param mensagem o que aconteceu, para a tela
     * @param data     o dia de onde o PDF saiu
     * @param dia      esse dia depois da mudança (null = ele ficou sem batidas e saiu)
     */
    public record Resultado(String mensagem, LocalDate data, RegistroJornadaView dia) {
    }

    /**
     * A própria pessoa tira do ponto um comprovante que não é dela.
     *
     * @param donoId        quem pede (só o próprio ponto)
     * @param comprovanteId o PDF arquivado (o que a tela mostra em cada batida)
     */
    @Transactional
    public Resultado desfazer(UUID donoId, UUID comprovanteId, String motivo, String quem) {
        String texto = motivo(motivo);
        ComprovanteArquivado arquivo = arquivo(comprovanteId);
        RegistroJornada registro = registroDe(arquivo);
        if (!registro.getUsuarioId().equals(donoId)) {
            throw naoEncontrado(); // o de outra pessoa nem aparece
        }
        Resultado resultado = tirar(registro, arquivo, "Importação desfeita: " + texto, quem);
        log.info("Importação do comprovante {} ({}) desfeita por {}: {}", arquivo.nomeOriginal(),
                momento(arquivo), quem, texto);
        return resultado;
    }

    /**
     * Quem configura o ponto da equipe manda o comprovante para a pessoa certa: sai de quem está e entra no ponto do
     * destino. Se ele não puder entrar lá (dia com a batida já, mês ou regra), nada muda.
     */
    @Transactional
    public Resultado mover(UUID comprovanteId, UUID destinoId, String motivo, String quem) {
        String texto = motivo(motivo);
        ComprovanteArquivado arquivo = arquivo(comprovanteId);
        RegistroJornada registro = registroDe(arquivo);
        UUID origemId = registro.getUsuarioId();
        if (origemId.equals(destinoId)) {
            throw new RegraNegocioException("MOVER_PARA_A_MESMA_PESSOA", "O comprovante já está no ponto desta pessoa.");
        }
        Usuario destino = usuarios.buscarPorId(destinoId).filter(Usuario::ativo)
                .orElseThrow(() -> new RecursoNaoEncontradoException("USUARIO_NAO_ENCONTRADO",
                        "A pessoa escolhida não foi encontrada (ou está desativada)."));
        byte[] conteudo;
        try {
            conteudo = armazenamento.ler(arquivo.caminhoArquivo());
        } catch (IOException | RuntimeException e) {
            log.warn("O comprovante {} não pôde ser lido para mover: {}", arquivo.id(), e.toString());
            throw new RegraNegocioException("COMPROVANTE_ILEGIVEL",
                    "O PDF deste comprovante não pôde ser lido no armazenamento. Nada mudou.");
        }
        Resultado saiu = tirar(registro, arquivo, "Comprovante movido para %s: %s".formatted(destino.nome(), texto),
                quem);

        Optional<ImportarComprovanteUseCase.Resultado> entrou = importar.executar(destinoId,
                new ImportarComprovanteUseCase.Comprovante(arquivo.nomeOriginal(), arquivo.hashSha256(),
                        Optional.of(arquivo.dataHoraBatida()), conteudo));
        if (entrou.isEmpty()) {
            throw new RegraNegocioException("MOVER_RECUSADO", ("%s já tirou este comprovante do ponto dela antes (não "
                    + "é dela). Nada mudou.").formatted(destino.nome()));
        }
        StatusImportacao status = entrou.get().status();
        if (status != StatusImportacao.IMPORTADO && status != StatusImportacao.DUPLICADO) {
            throw new RegraNegocioException("MOVER_RECUSADO", "O comprovante não entrou no ponto de %s: %s Nada mudou."
                    .formatted(destino.nome(), entrou.get().mensagem()));
        }
        log.info("Comprovante {} ({}) movido para {} por {}: {}", arquivo.nomeOriginal(), momento(arquivo),
                destino.login(), quem, texto);
        return new Resultado(saiu.mensagem() + " No ponto de %s: %s".formatted(destino.nome(), entrou.get().mensagem()),
                saiu.data(), saiu.dia());
    }

    // ------------------------------------------------------------------ o PDF sai do dia

    private Resultado tirar(RegistroJornada registro, ComprovanteArquivado arquivo, String justificativa, String quem) {
        UUID donoId = registro.getUsuarioId();
        trava.exigirAberto(donoId, registro.getDataReferencia());
        Optional<ComprovanteImportado> importado = comprovantes.buscarPorHash(donoId, arquivo.hashSha256());
        // a batida veio do PDF? (sem o registro da importação, a de mesmo horário exato)
        LocalTime horario = arquivo.dataHoraBatida().toLocalTime();
        Optional<LocalTime> batida = VinculoComprovantes.tipoComprovado(registro.getBatidas(), horario)
                .map(t -> registro.getBatidas().horario(t));
        boolean criouABatida = importado.map(i -> i.status() == StatusImportacao.IMPORTADO)
                .orElse(batida.filter(horario::equals).isPresent());

        arquivos.excluir(arquivo.id());
        String quando = momento(arquivo);
        if (importado.isPresent()) {
            comprovantes.marcarDesfeito(importado.get().id(), "%s (por %s)".formatted(justificativa, quem));
        } else {
            comprovantes.salvar(ComprovanteImportado.novo(donoId, arquivo.nomeOriginal(), arquivo.hashSha256(),
                    arquivo.dataHoraBatida(), StatusImportacao.DESFEITO, "%s (por %s)".formatted(justificativa, quem)));
        }
        armazenamento.guardarComoDesfeito(arquivo.caminhoArquivo());

        if (!criouABatida || batida.isEmpty()) {
            // a batida foi registrada por outro meio (o PDF só a comprovava): ela fica, sem o PDF
            RegistroJornadaView view = RegistroJornadaView.de(registro, regras.motor(registro));
            String mensagem = "Comprovante de %s tirado do dia; a batida das %s fica (não veio dele)."
                    .formatted(quando, HORA.format(batida.orElse(horario)));
            eventos.publishEvent(new JornadaAtualizadaEvento(donoId, registro.getDataReferencia(),
                    OrigemAtualizacao.AJUSTE, view, mensagem));
            return new Resultado(mensagem, registro.getDataReferencia(), view);
        }

        List<LocalTime> antes = registro.getBatidas().horarios();
        List<LocalTime> depois = new ArrayList<>(antes);
        depois.remove(batida.get());
        if (depois.isEmpty()) {
            historico.salvar(new AjusteJornada(UUID.randomUUID(), donoId, registro.getId(), registro.getDataReferencia(),
                    antes, List.of(), justificativa, quem, clock.instant(), AjusteJornada.Origem.DESFEITO));
            registros.excluir(registro);
            String mensagem = "Batida das %s de %s tirada; o dia ficou sem batidas."
                    .formatted(HORA.format(batida.get()), DIA.format(registro.getDataReferencia()));
            eventos.publishEvent(new JornadaAtualizadaEvento(donoId, registro.getDataReferencia(),
                    OrigemAtualizacao.EXCLUSAO, null, mensagem));
            return new Resultado(mensagem, registro.getDataReferencia(), null);
        }

        MotorCalculoJornadaService motor = regras.motor(registro);
        Set<LocalTime> comprovadas = VinculoComprovantes.horariosComprovados(registro,
                arquivos.listarPorRegistro(registro.getId()));
        registro.ajustarBatidas(depois, comprovadas, SO_TIRAR, motor);
        registros.salvar(registro);
        VinculoComprovantes.reorganizar(registro, arquivos);
        historico.salvar(AjusteJornada.novo(registro, antes, justificativa, quem, clock.instant(),
                AjusteJornada.Origem.DESFEITO));
        RegistroJornadaView view = RegistroJornadaView.de(registro, motor);
        String mensagem = "Batida das %s de %s tirada (o comprovante não era deste ponto)."
                .formatted(HORA.format(batida.get()), DIA.format(registro.getDataReferencia()));
        eventos.publishEvent(new JornadaAtualizadaEvento(donoId, registro.getDataReferencia(), OrigemAtualizacao.AJUSTE,
                view, mensagem));
        return new Resultado(mensagem, registro.getDataReferencia(), view);
    }

    // ------------------------------------------------------------------ apoio

    private static String motivo(String motivo) {
        String texto = motivo == null ? "" : motivo.strip();
        if (texto.length() < MOTIVO_MINIMO) {
            throw new RegraNegocioException("DESFAZER_SEM_MOTIVO",
                    "Escreva por que o comprovante sai deste ponto (ex.: \"É da Maria: caiu na minha pasta\").");
        }
        if (texto.length() > MOTIVO_MAXIMO) {
            throw new RegraNegocioException("DESFAZER_MOTIVO_LONGO",
                    "O motivo pode ter no máximo %d caracteres.".formatted(MOTIVO_MAXIMO));
        }
        return texto;
    }

    private ComprovanteArquivado arquivo(UUID id) {
        return arquivos.buscarPorId(id).orElseThrow(DesfazerImportacaoUseCase::naoEncontrado);
    }

    private RegistroJornada registroDe(ComprovanteArquivado arquivo) {
        return registros.buscarPorId(arquivo.registroJornadaId()).orElseThrow(DesfazerImportacaoUseCase::naoEncontrado);
    }

    private static RecursoNaoEncontradoException naoEncontrado() {
        return new RecursoNaoEncontradoException("COMPROVANTE_NAO_ENCONTRADO",
                "Comprovante não encontrado. Atualize a tela: ele pode já ter saído do ponto.");
    }

    private static String momento(ComprovanteArquivado arquivo) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").format(arquivo.dataHoraBatida());
    }
}
