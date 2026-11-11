package br.com.conferenciaponto.modulos.fiscal.domain;

/**
 * Os motivos de "sem informação" que são deste computador, não da SEFAZ: sem internet, a internet oscilando (muitos
 * servidores sem resposta ao mesmo tempo) e o DNS que não acha o endereço. Nenhum deles abre incidente nem pinta o
 * quadro de vermelho: autorizadores independentes não caem todos juntos, e um nome da SEFAZ não some do DNS.
 */
public final class ProblemaLocal {

    public static final String SEM_INTERNET = "Sem internet neste computador: nenhuma fonte respondeu nesta rodada.";
    public static final String REDE_OSCILANDO = "Muitos servidores sem resposta ao mesmo tempo: provavelmente a internet "
            + "deste computador oscilou (a SEFAZ não foi conferida nesta leitura).";
    public static final String DNS = "Endereço não encontrado no DNS deste computador (confira a internet; se "
            + "continuar, o endereço no fiscal.yml).";
    public static final String SEM_ROTA = "Sem rota até o servidor a partir deste computador.";

    private ProblemaLocal() {
    }

    /** A internet deste computador (sem internet ou oscilando), o que a tela avisa no alto. */
    public static boolean daInternet(String detalhe) {
        return SEM_INTERNET.equals(detalhe) || REDE_OSCILANDO.equals(detalhe);
    }
}
