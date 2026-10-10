package br.com.conferenciaponto.modulos.atendimento.domain.ia;

import java.util.Objects;

/**
 * Um pedido de geração.
 *
 * @param instrucaoDoSistema as regras comuns (só o visível ou audível, "não identificado", nada de dados de acesso)
 * @param instrucao          o que fazer com este arquivo
 * @param esquemaJson        o JSON Schema da resposta
 * @param midia              o arquivo, no próprio pedido ou já enviado (null num pedido só de texto)
 * @param altaResolucao      imagens em alta resolução (telas com letra pequena)
 * @param temperatura        null = a padrão do modelo
 * @param raciocinio         nível de raciocínio (ex.: low); null = o padrão do modelo
 */
public record PedidoAoModelo(String modelo, String instrucaoDoSistema, String instrucao, String esquemaJson, Midia midia,
                             boolean altaResolucao, Double temperatura, String raciocinio, int maxTokensSaida) {

    public PedidoAoModelo {
        Objects.requireNonNull(modelo, "modelo");
        Objects.requireNonNull(instrucao, "instrucao");
        Objects.requireNonNull(esquemaJson, "esquemaJson");
    }

    /** O arquivo que vai com o pedido: os bytes (imagem pequena) ou o endereço de um arquivo já enviado. */
    public record Midia(String tipoDeMidia, byte[] dados, String uri) {

        public Midia {
            Objects.requireNonNull(tipoDeMidia, "tipoDeMidia");
            if ((dados == null) == (uri == null)) {
                throw new IllegalArgumentException("Informe os bytes ou o endereço do arquivo, um dos dois");
            }
        }

        public static Midia noPedido(String tipoDeMidia, byte[] dados) {
            return new Midia(tipoDeMidia, dados, null);
        }

        public static Midia enviada(ArquivoRemoto arquivo) {
            return new Midia(arquivo.tipoDeMidia(), null, arquivo.uri());
        }

        @Override
        public String toString() {
            return dados != null ? "Midia[" + tipoDeMidia + ", " + dados.length + " bytes]" : "Midia[" + tipoDeMidia + ", enviada]";
        }
    }
}
