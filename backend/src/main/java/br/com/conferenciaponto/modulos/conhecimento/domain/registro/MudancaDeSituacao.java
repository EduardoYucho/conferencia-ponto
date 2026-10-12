package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import java.time.Instant;

/**
 * Uma linha do histórico da situação.
 *
 * @param de  a situação anterior (null na criação do registro)
 * @param por o login de quem mudou
 */
public record MudancaDeSituacao(SituacaoDoRegistro de, SituacaoDoRegistro para, String chamadoDev,
                                String versaoCorrecao, String por, Instant em) {
}
