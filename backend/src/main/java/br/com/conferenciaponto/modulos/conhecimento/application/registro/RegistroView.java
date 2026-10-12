package br.com.conferenciaponto.modulos.conhecimento.application.registro;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Um registro aberto na tela.
 *
 * @param texto        o texto confirmado (HTML limpo pelo servidor), com "Imagem N" no lugar das imagens
 * @param versao       vai de volta em cada mudança (controle da edição simultânea)
 * @param podeEditar   quem está vendo é o autor ou cura a base: edita, muda a situação, valida e apaga
 * @param atendimentoId o atendimento de origem (pode já ter sido apagado pela retenção)
 * @param naPesquisaPorSignificado o registro já tem vetor (sem ele, aparece só na pesquisa exata)
 */
public record RegistroView(UUID id, String origem, String tipo, String tipoRotulo, String titulo, String cliente,
                           String contato, String modulo, String telaNome, String telaCodigo, String sintoma,
                           String mensagemErro, String causa, String resolucao, String texto, String situacao,
                           String situacaoRotulo, String chamadoDev, String versaoCorrecao, boolean solucaoValidada,
                           String validadaPor, Instant validadaEm, int versao, Instant criadoEm, Instant atualizadoEm,
                           Autor autor, UUID atendimentoId, UUID saidaId, List<Imagem> imagens, List<Mudanca> historico,
                           boolean podeEditar, boolean souAutor, boolean naPesquisaPorSignificado) {

    public record Autor(UUID id, String nome, String login) {
    }

    /**
     * @param disponivel o arquivo está na pasta da base
     * @param arquivo    Imagem_01.png
     */
    public record Imagem(int numero, String legenda, boolean disponivel, String arquivo) {
    }

    /** Uma linha do histórico da situação (de = null na criação). */
    public record Mudanca(String de, String deRotulo, String para, String paraRotulo, String chamadoDev,
                          String versaoCorrecao, String por, Instant em) {
    }
}
