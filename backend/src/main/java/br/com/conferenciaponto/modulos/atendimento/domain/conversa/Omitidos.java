package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

/** Quantos trechos o {@link Mascaramento} trocou por [omitido], por motivo. */
public record Omitidos(int chaveDoBot, int acessoRemoto) {

    public static final Omitidos NENHUM = new Omitidos(0, 0);

    public int total() {
        return chaveDoBot + acessoRemoto;
    }

    public Omitidos mais(Omitidos outros) {
        return new Omitidos(chaveDoBot + outros.chaveDoBot, acessoRemoto + outros.acessoRemoto);
    }
}
