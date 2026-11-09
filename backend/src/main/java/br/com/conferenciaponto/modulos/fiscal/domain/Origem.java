package br.com.conferenciaponto.modulos.fiscal.domain;

/** De onde veio a leitura. */
public enum Origem {
    /** Painel de disponibilidade da SVRS (o portal consulta os serviços e publica o retorno de cada um). */
    PAINEL_SVRS("painel_svrs"),
    /** Teste de conexão feito daqui no endereço do autorizador (sem certificado: responde ou não, e em quanto tempo). */
    TESTE_DE_CONEXAO("teste_conexao");

    private final String codigo;

    Origem(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

    public static Origem doCodigo(String codigo) {
        for (Origem o : values()) {
            if (o.codigo.equals(codigo)) {
                return o;
            }
        }
        throw new IllegalArgumentException("Origem desconhecida: " + codigo);
    }
}
