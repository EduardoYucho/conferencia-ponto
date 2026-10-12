package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

/**
 * O que a pessoa informa para os chamados do desenvolvimento (não está em nenhuma fonte do atendimento).
 *
 * @param versaoCliente       a versão do sistema no cliente; nos chamados reais é a data da última atualização, e
 *                            vale como um dado só (sai no cabeçalho e no rodapé)
 * @param testadoUltimaVersao null = não informado
 * @param caminhoBackup       ex.: a pasta do backup do cliente no servidor da empresa
 * @param chamadoRelacionado  link ou número de um chamado anterior (opcional)
 */
public record CamposInformados(String versaoCliente, Boolean testadoUltimaVersao, String caminhoBackup,
                               String chamadoRelacionado) {

    public static final CamposInformados VAZIO = new CamposInformados(null, null, null, null);
    public static final int LIMITE_DA_VERSAO = 40;
    public static final int LIMITE_DO_BACKUP = 300;
    public static final int LIMITE_DO_CHAMADO = 200;

    public CamposInformados {
        versaoCliente = limpo(versaoCliente);
        caminhoBackup = limpo(caminhoBackup);
        chamadoRelacionado = limpo(chamadoRelacionado);
    }

    private static String limpo(String texto) {
        if (texto == null) {
            return null;
        }
        String t = texto.strip().replaceAll("[\\r\\n\\t]+", " ");
        return t.isEmpty() ? null : t;
    }
}
