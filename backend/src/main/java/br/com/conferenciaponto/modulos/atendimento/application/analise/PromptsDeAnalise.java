package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * As instruções e os esquemas da análise de cada tipo de arquivo. A versão fica gravada com a análise: mudar o
 * texto de um prompt exige subir a versão dele (as análises antigas continuam valendo para o que já foi redigido, e
 * os arquivos são analisados de novo só quando a pessoa processar outra vez).
 *
 * <p>Vídeo fica para a etapa do vídeo (quadros e passos); "outro" não é analisado.
 */
public final class PromptsDeAnalise {

    /**
     * Um prompt.
     *
     * @param obrigatorios os campos do topo que a resposta precisa ter (a conferência antes de gravar)
     * @param listas       quais desses campos são listas
     */
    public record Prompt(String tipo, int versao, String instrucao, String esquema, List<String> obrigatorios,
                         List<String> listas, boolean altaResolucao) {
    }

    /** As regras comuns, que valem para qualquer arquivo. */
    public static final String SISTEMA = """
            Você analisa arquivos de atendimentos de suporte técnico de um sistema de gestão (ERP) para empresas. O \
            resultado será usado para escrever o resumo do atendimento e chamados para a equipe de desenvolvimento.

            Regras:
            - Descreva só o que está visível ou audível no arquivo. Não invente, não complete e não deduza o que não \
            aparece.
            - O que não der para ler ou entender, escreva exatamente "não identificado". Campo de texto sem \
            informação: "não identificado". Lista sem itens: lista vazia.
            - Copie mensagens, nomes de telas, códigos e valores exatamente como aparecem (mesma grafia, maiúsculas e \
            pontuação).
            - Nunca reproduza IDs, senhas ou códigos de acesso remoto (UltraViewer, AnyDesk, TeamViewer e outros), \
            chaves de ativação, tokens ou senhas de qualquer tipo: escreva "[omitido]" no lugar.
            - Escreva em português do Brasil.
            - Responda só com o JSON no esquema pedido.""";

    private static final Prompt IMAGEM = new Prompt("imagem", 1, """
            Analise esta imagem de um atendimento (print de tela, foto da tela ou documento fotografado).
            - "descricao": o que a imagem mostra, em 1 a 3 frases.
            - "tela": o nome (ou título), o código e o caminho de menu da tela do sistema, se aparecerem.
            - "campos": os campos e valores visíveis que importam para entender o problema (rótulo e valor).
            - "mensagens": as mensagens de erro, aviso, informação ou confirmação, com o texto literal.
            - "textoLegivel": se o texto da imagem dá para ler.
            - "regioes": até 5 áreas de interesse (a mensagem de erro, o campo com problema), cada uma com a caixa \
            [ymin, xmin, ymax, xmax] em coordenadas de 0 a 1000.""", """
            {
              "type": "object",
              "properties": {
                "descricao": {"type": "string", "description": "O que a imagem mostra, em 1 a 3 frases."},
                "tela": {
                  "type": "object",
                  "properties": {
                    "nome": {"type": "string", "description": "Nome ou título da tela, como aparece."},
                    "codigo": {"type": "string", "description": "Código da tela ou da rotina, se aparecer."},
                    "caminhoMenu": {"type": "string", "description": "Caminho de menu visível (ex.: Vendas > Pedidos)."}
                  },
                  "required": ["nome", "codigo", "caminhoMenu"]
                },
                "campos": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {"rotulo": {"type": "string"}, "valor": {"type": "string"}},
                    "required": ["rotulo", "valor"]
                  }
                },
                "mensagens": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "texto": {"type": "string", "description": "Texto literal, como está na imagem."},
                      "tipo": {"type": "string", "enum": ["erro", "aviso", "informacao", "confirmacao", "outro"]}
                    },
                    "required": ["texto", "tipo"]
                  }
                },
                "textoLegivel": {"type": "boolean"},
                "regioes": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "descricao": {"type": "string"},
                      "caixa": {"type": "array", "items": {"type": "integer"}, "description": "[ymin, xmin, ymax, xmax], de 0 a 1000."}
                    },
                    "required": ["descricao", "caixa"]
                  }
                }
              },
              "required": ["descricao", "tela", "campos", "mensagens", "textoLegivel", "regioes"]
            }""", List.of("descricao", "tela", "campos", "mensagens", "textoLegivel", "regioes"),
            List.of("campos", "mensagens", "regioes"), true);

    private static final Prompt AUDIO = new Prompt("audio", 1, """
            Transcreva este áudio de um atendimento (gravação de ligação ou mensagem de voz) completo, sem resumir, \
            na ordem em que foi falado.
            - "trechos": cada fala, com o instante de início (MM:SS), quem fala e o texto. Não corrija o que foi dito.
            - "falantes": cada pessoa que fala, com o papel (cliente ou atendente) quando der para saber pelo \
            contexto; senão, "nao_identificado".
            - Trecho que não dá para entender: escreva [inaudível] no lugar das palavras e marque \
            "temTrechoInaudivel".
            - "duracao": a duração do áudio (MM:SS).""", """
            {
              "type": "object",
              "properties": {
                "idioma": {"type": "string"},
                "duracao": {"type": "string", "description": "MM:SS"},
                "falantes": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "rotulo": {"type": "string", "description": "Como aparece nos trechos (ex.: Falante 1)."},
                      "papel": {"type": "string", "enum": ["cliente", "atendente", "outro", "nao_identificado"]}
                    },
                    "required": ["rotulo", "papel"]
                  }
                },
                "trechos": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "inicio": {"type": "string", "description": "MM:SS"},
                      "falante": {"type": "string"},
                      "texto": {"type": "string"}
                    },
                    "required": ["inicio", "falante", "texto"]
                  }
                },
                "temTrechoInaudivel": {"type": "boolean"}
              },
              "required": ["idioma", "duracao", "falantes", "trechos", "temTrechoInaudivel"]
            }""", List.of("idioma", "duracao", "falantes", "trechos", "temTrechoInaudivel"),
            List.of("falantes", "trechos"), false);

    private static final Prompt DOCUMENTO = new Prompt("documento", 1, """
            Leia este documento de um atendimento.
            - "tipoDocumento": o tipo (ex.: nota fiscal, relatório, log, planilha exportada, contrato, outro).
            - "descricao": o que é o documento, em 1 a 3 frases.
            - "conteudo": o texto principal, transcrito; em documentos longos, as partes que importam para o suporte \
            (números, datas, valores, mensagens).
            - "dados": os dados relevantes (rótulo e valor).
            - "mensagens": as mensagens de erro ou aviso que aparecem, com o texto literal.""", """
            {
              "type": "object",
              "properties": {
                "tipoDocumento": {"type": "string"},
                "descricao": {"type": "string"},
                "conteudo": {"type": "string"},
                "dados": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {"rotulo": {"type": "string"}, "valor": {"type": "string"}},
                    "required": ["rotulo", "valor"]
                  }
                },
                "mensagens": {"type": "array", "items": {"type": "string"}}
              },
              "required": ["tipoDocumento", "descricao", "conteudo", "dados", "mensagens"]
            }""", List.of("tipoDocumento", "descricao", "conteudo", "dados", "mensagens"),
            List.of("dados", "mensagens"), false);

    private static final Map<CategoriaDoArquivo, Prompt> POR_CATEGORIA = Map.of(
            CategoriaDoArquivo.IMAGEM, IMAGEM,
            CategoriaDoArquivo.AUDIO, AUDIO,
            CategoriaDoArquivo.DOCUMENTO, DOCUMENTO);

    private PromptsDeAnalise() {
    }

    /** O prompt da categoria, ou vazio quando o tipo não é analisado (vídeo nesta versão, "outro"). */
    public static Optional<Prompt> para(CategoriaDoArquivo categoria) {
        return Optional.ofNullable(categoria == null ? null : POR_CATEGORIA.get(categoria));
    }
}
