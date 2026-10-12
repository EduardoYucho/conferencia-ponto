package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Uma saída aberta na tela de resultado.
 *
 * @param html            o texto que vale agora (o editado, se houver), gerado ou limpo pelo servidor; os
 *                        parágrafos e passos levam {@code data-fontes} e o parágrafo de cada imagem, {@code data-imagem}
 * @param editado         a pessoa mexeu no texto ou nas imagens (dá para desfazer)
 * @param avisos          o que a conferência do servidor mudou ou quer que a pessoa confira
 * @param base            os campos para a base de conhecimento (na etapa da base, viram o formulário de confirmação)
 * @param imagensApagadas os arquivos do atendimento já foram apagados (prazo de retenção): as imagens não abrem mais
 * @param cabecalho       nos chamados para o desenvolvimento: Tela/Funcionalidade, Versão do cliente e Testado na
 *                        última versão, montados na hora com os campos informados (vazio no resumo)
 * @param rodape          nos chamados: a data de atualização (no de erro) e o caminho do backup (vazio no resumo)
 * @param variantes       nos chamados de erro: comparacao, sem_causa_identificada, apos_atualizacao
 */
public record SaidaView(UUID id, String tipo, String rotulo, int versao, String situacao, String erro, String assunto,
                        int limiteDoAssunto, String html, boolean editado, List<String> avisos, Map<String, String> base,
                        String telaFuncionalidade, List<Imagem> imagens, String modelo, Integer versaoPrompt,
                        Integer tokensEntrada, Integer tokensSaida, Instant criadaEm, boolean imagensApagadas,
                        String cabecalho, String rodape, List<String> variantes) {

    /**
     * Uma imagem da saída.
     *
     * @param incluida   está no texto (as tiradas ficam para poder voltar)
     * @param disponivel o arquivo ainda está no servidor
     * @param arquivo    o nome no .zip e para anexar ao chamado (ex.: Imagem_01.png)
     */
    public record Imagem(UUID id, int numero, String legenda, boolean incluida, boolean disponivel, String arquivo) {
    }
}
