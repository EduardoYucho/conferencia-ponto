package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import java.time.Instant;
import java.util.UUID;

/**
 * Um caso da base como está no banco.
 *
 * @param origem         gerador (veio de um texto confirmado) ou importado
 * @param atendimentoId  o atendimento que gerou o registro (pode já ter sido apagado pela retenção)
 * @param resolucaoHtml  o texto confirmado, como foi para o sistema de chamados ("Imagem N" no lugar das imagens)
 * @param versao         controle da edição simultânea: muda a cada alteração
 */
public record Registro(UUID id, String origem, UUID atendimentoId, UUID saidaId, UUID autorId, String autorNome,
                       String autorLogin, DadosDoRegistro dados, String resolucaoHtml, SituacaoDoRegistro situacao,
                       String chamadoDev, String versaoCorrecao, boolean solucaoValidada, String validadaPor,
                       Instant validadaEm, int versao, Instant criadoEm, Instant atualizadoEm) {

    @Override
    public String toString() {
        return "Registro[" + id + ", " + dados.tipo().codigo() + ", " + situacao.codigo() + ", v" + versao + "]";
    }
}
