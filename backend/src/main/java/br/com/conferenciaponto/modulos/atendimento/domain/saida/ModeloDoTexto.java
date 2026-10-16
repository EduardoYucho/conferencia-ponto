package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A estrutura de cada tipo de texto: o resumo é corrido (parágrafos e passos); os chamados para o desenvolvimento têm
 * as partes dos chamados reais, sempre na mesma ordem, cada uma com o título montado pelo servidor. O Gemini preenche
 * as partes que o material permite; parte vazia some do texto, menos as que têm o que dizer quando vazias (ex.: a
 * causa de um erro: "Causa não identificada.").
 *
 * @param abertura a primeira linha do texto (ex.: "Solicitação de Melhoria"), ou null
 */
public record ModeloDoTexto(String abertura, List<Secao> secoes) {

    /**
     * Uma parte do chamado.
     *
     * @param chave  como vem no JSON do Gemini
     * @param passos os trechos são passos (um parágrafo curto cada, com a imagem logo depois; nada é numerado)
     * @param seVazia o que o sistema escreve quando a parte vem vazia (null = a parte some)
     */
    public record Secao(String chave, String titulo, boolean passos, String seVazia) {
    }

    public static final String CAUSA_NAO_IDENTIFICADA = "Causa não identificada.";

    public static final ModeloDoTexto RESUMO = new ModeloDoTexto(null, List.of());

    /** Chamado de erro: as partes reunidas dos três chamados reais, com as variantes (comparação, sem causa, após atualização). */
    public static final ModeloDoTexto DEV_ERRO = new ModeloDoTexto(null, List.of(
            new Secao("caminhoTela", "Caminho da tela", false, null),
            new Secao("relato", "Relato do problema", false, null),
            new Secao("comparacao", "Comparação", false, null),
            new Secao("passos", "Passos para reproduzir", true, null),
            new Secao("resultadoEsperado", "Resultado esperado", false, null),
            new Secao("resultadoObtido", "Resultado obtido", false, null),
            new Secao("dadosDoRegistro", "Dados do registro afetado", false, null),
            new Secao("desdeQuando", "Desde quando ocorre", false, null),
            new Secao("tentativas", "O que já foi verificado e tentado", false, null),
            new Secao("causa", "Causa", false, CAUSA_NAO_IDENTIFICADA),
            new Secao("pedido", "Pedido ao desenvolvimento", false, null)));

    /**
     * Chamado de alteração: as partes do chamado real mais completo, mais o detalhamento (colunas, campos, filtros) e
     * os pontos a confirmar com o cliente (o que o desenvolvimento precisa e o atendimento não disse).
     */
    public static final ModeloDoTexto DEV_ALTERACAO = new ModeloDoTexto("Solicitação de Melhoria", List.of(
            new Secao("modulo", "Módulo e telas envolvidas", false, null),
            new Secao("tipoDeSolicitacao", "Tipo de solicitação", false, null),
            new Secao("descricao", "Descrição resumida", false, null),
            new Secao("contexto", "Contexto e objetivo", false, null),
            new Secao("comportamentoAtual", "Comportamento atual", false, null),
            new Secao("comportamentoEsperado", "Comportamento esperado", false, null),
            new Secao("detalhamento", "Detalhamento do que criar ou mudar", true, null),
            new Secao("regras", "Regras de negócio sugeridas", false, null),
            new Secao("observacaoComercial", "Observação comercial", false, null),
            new Secao("pontosAConfirmar", "Pontos a confirmar com o cliente", true, null)));

    public ModeloDoTexto {
        secoes = List.copyOf(secoes);
    }

    public static ModeloDoTexto de(TipoDeSaida tipo) {
        return switch (tipo) {
            case RESUMO -> RESUMO;
            case DEV_ERRO -> DEV_ERRO;
            case DEV_ALTERACAO -> DEV_ALTERACAO;
        };
    }

    /** O texto é dividido em partes (os chamados para o desenvolvimento). */
    public boolean comSecoes() {
        return !secoes.isEmpty();
    }

    public Optional<Secao> secao(String chave) {
        return secoes.stream().filter(s -> s.chave().equals(chave)).findFirst();
    }

    /** O título de cada parte, pela chave. */
    public Map<String, String> titulos() {
        Map<String, String> titulos = new LinkedHashMap<>();
        secoes.forEach(s -> titulos.put(s.chave(), s.titulo()));
        return titulos;
    }

    /**
     * Os trechos de cada parte, na ordem do modelo (as partes que o modelo não conhece saem). Nas partes de passos,
     * todo trecho vira passo.
     */
    public List<ConferenciaDoTexto.BlocoBruto> ordenar(Map<String, List<ConferenciaDoTexto.BlocoBruto>> porSecao) {
        List<ConferenciaDoTexto.BlocoBruto> ordenados = new ArrayList<>();
        for (Secao secao : secoes) {
            for (ConferenciaDoTexto.BlocoBruto b : porSecao.getOrDefault(secao.chave(), List.of())) {
                if (b == null) {
                    continue;
                }
                ordenados.add(new ConferenciaDoTexto.BlocoBruto(secao.passos() ? "passo" : b.tipo(), b.texto(), b.fontes(),
                        b.imagens(), secao.chave()));
            }
        }
        return ordenados;
    }

    /** As partes que vieram vazias e têm o que dizer assim entram com o texto do sistema, no lugar delas. */
    public List<BlocoDoTexto> completar(List<BlocoDoTexto> blocos) {
        if (!comSecoes()) {
            return blocos;
        }
        List<BlocoDoTexto> completos = new ArrayList<>();
        for (Secao secao : secoes) {
            List<BlocoDoTexto> daSecao = blocos.stream().filter(b -> secao.chave().equals(b.secao())).toList();
            if (daSecao.isEmpty() && secao.seVazia() != null) {
                completos.add(BlocoDoTexto.doSistema(secao.chave(), secao.seVazia()));
            } else {
                completos.addAll(daSecao);
            }
        }
        return completos;
    }
}
