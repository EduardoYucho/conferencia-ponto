package br.com.conferenciaponto.modulos.conhecimento.application.pesquisa;

import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Trecho;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Um resultado da pesquisa.
 *
 * @param titulo     o título em partes, com o que bateu destacado
 * @param telaCodigo o código da tela em partes (o pedaço pesquisado destacado)
 * @param trecho     um pedaço do sintoma, do erro, da causa ou da resolução, com o que bateu destacado
 */
public record RegistroEncontrado(UUID id, String tipo, String tipoRotulo, String situacao, String situacaoRotulo,
                                 List<Trecho.Parte> titulo, String telaNome, List<Trecho.Parte> telaCodigo, String cliente,
                                 String modulo, String chamadoDev, String versaoCorrecao, boolean solucaoValidada,
                                 String autor, Instant criadoEm, List<Trecho.Parte> trecho) {
}
