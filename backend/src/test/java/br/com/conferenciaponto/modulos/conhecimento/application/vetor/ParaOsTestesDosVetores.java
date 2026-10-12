package br.com.conferenciaponto.modulos.conhecimento.application.vetor;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.DadosDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.NovoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.SituacaoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.TipoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence.RepositoriosDaBase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Registros e vetores no banco compartilhado pelos testes: cada teste usa um modelo de vetores só dele (os vetores de
 * outros testes não entram no índice) e cria os registros com data antiga, para virem primeiro na fila dos pendentes.
 */
public final class ParaOsTestesDosVetores {

    public static final int DIMENSAO = 256;
    private static final Instant ANTIGO = Instant.parse("2001-01-01T12:00:00Z");

    public final Registros registros = RepositoriosDaBase.registros();
    public final String modelo = "teste-" + UUID.randomUUID().toString().substring(0, 8);
    private int sequencia;

    public ConfiguracaoDosVetores configuracao(double naPesquisa, double deParecido) {
        return new ConfiguracaoDosVetores(modelo, DIMENSAO, naPesquisa, 30, 50, 5, deParecido);
    }

    public UUID registro(UUID autor, String titulo, String sintoma, String erro, String telaCodigo, UUID atendimento) {
        UUID id = UUID.randomUUID();
        registros.criar(new NovoRegistro(id, atendimento == null ? UUID.randomUUID() : atendimento, UUID.randomUUID(), autor,
                "autor", new DadosDoRegistro(titulo, TipoDoRegistro.ERRO, null, null, null, null, telaCodigo, sintoma, erro,
                null, null), null, SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, "OTRS " + (++sequencia),
                ANTIGO.plusSeconds(sequencia)), List.of());
        return id;
    }

    public static UUID autor() {
        return PostgresDeTeste.novoUsuario(true);
    }
}
