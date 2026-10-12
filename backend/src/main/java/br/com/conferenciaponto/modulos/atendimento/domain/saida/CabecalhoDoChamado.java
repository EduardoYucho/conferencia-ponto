package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.CamposInformados;

/**
 * O cabeçalho e o rodapé dos chamados para o desenvolvimento, montados pelo servidor com os campos que a pessoa
 * informa (não estão em nenhuma fonte): Tela/Funcionalidade, Versão do cliente e Testado na última versão no
 * cabeçalho; data de atualização do sistema (a mesma informação da versão do cliente) e caminho do backup no rodapé.
 * Campo vazio sai como "não informado". Como são montados na hora, mudar um campo não exige gerar o texto de novo.
 */
public final class CabecalhoDoChamado {

    public static final String NAO_INFORMADO = "não informado";

    private CabecalhoDoChamado() {
    }

    /** O cabeçalho do tipo (vazio no resumo). */
    public static String cabecalho(TipoDeSaida tipo, CamposInformados campos, String telaFuncionalidade) {
        if (tipo == TipoDeSaida.RESUMO) {
            return "";
        }
        CamposInformados c = campos == null ? CamposInformados.VAZIO : campos;
        StringBuilder html = new StringBuilder();
        linha(html, "Tela/Funcionalidade", valor(telaFuncionalidade));
        linha(html, "Versão do cliente", valor(c.versaoCliente()));
        linha(html, "Testado na última versão disponível", c.testadoUltimaVersao() == null ? NAO_INFORMADO
                : c.testadoUltimaVersao() ? "Sim" : "Não");
        if (c.chamadoRelacionado() != null && !c.chamadoRelacionado().isBlank()) {
            linha(html, "Chamado relacionado", c.chamadoRelacionado().strip());
        }
        return html.toString();
    }

    /** O rodapé do tipo (vazio no resumo): o de erro leva a data de atualização; os dois, o caminho do backup. */
    public static String rodape(TipoDeSaida tipo, CamposInformados campos) {
        if (tipo == TipoDeSaida.RESUMO) {
            return "";
        }
        CamposInformados c = campos == null ? CamposInformados.VAZIO : campos;
        StringBuilder html = new StringBuilder();
        if (tipo == TipoDeSaida.DEV_ERRO) {
            linha(html, "Data de atualização do sistema", valor(c.versaoCliente()));
        }
        linha(html, "Caminho do backup", valor(c.caminhoBackup()));
        return html.toString();
    }

    private static String valor(String texto) {
        if (texto == null || texto.isBlank() || texto.strip().equalsIgnoreCase("não identificado")) {
            return NAO_INFORMADO;
        }
        return texto.strip();
    }

    private static void linha(StringBuilder html, String rotulo, String valor) {
        html.append("<p><strong>").append(MontagemDoHtml.escapar(rotulo)).append(":</strong> ")
                .append(MontagemDoHtml.escapar(valor)).append("</p>\n");
    }
}
