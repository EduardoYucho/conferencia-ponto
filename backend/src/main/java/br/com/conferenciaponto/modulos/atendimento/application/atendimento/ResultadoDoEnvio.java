package br.com.conferenciaponto.modulos.atendimento.application.atendimento;

/**
 * Resposta do envio do PDF.
 *
 * @param criado         false quando já havia um atendimento deste chamado (nada foi criado; a tela oferece abrir o
 *                       existente)
 * @param atendimento    o criado ou o que já existia
 * @param leitura        o que foi lido do PDF enviado
 * @param linksRenovados anexos do atendimento existente que ganharam o link novo deste PDF
 */
public record ResultadoDoEnvio(boolean criado, ResumoView atendimento, LeituraView leitura, int linksRenovados) {
}
