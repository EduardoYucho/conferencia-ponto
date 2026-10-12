package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O texto de uma saída depois da conferência do servidor (é o que fica em {@code saida.estrutura}).
 *
 * @param assunto            já no padrão {@code Digisac: <contato> : <assunto>}, com até 150 caracteres
 * @param base               os campos para a base de conhecimento (cliente, módulo, tela, código, sintoma, erro,
 *                           causa, resolução), na ordem
 * @param telaFuncionalidade a sugestão para o campo Tela/Funcionalidade dos chamados
 * @param avisos             o que a conferência mudou ou quer que a pessoa confira
 * @param variantes          nos chamados de erro, o que o Gemini identificou: comparacao, sem_causa_identificada,
 *                           apos_atualizacao
 */
public record TextoRedigido(int versao, String assunto, List<BlocoDoTexto> blocos, Map<String, String> base,
                            String telaFuncionalidade, List<String> avisos, List<String> variantes) {

    public static final int VERSAO = 1;

    public TextoRedigido {
        blocos = List.copyOf(blocos);
        base = base == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(base));
        avisos = List.copyOf(avisos);
        variantes = variantes == null ? List.of() : List.copyOf(variantes);
    }

    public TextoRedigido(int versao, String assunto, List<BlocoDoTexto> blocos, Map<String, String> base,
                         String telaFuncionalidade, List<String> avisos) {
        this(versao, assunto, blocos, base, telaFuncionalidade, avisos, List.of());
    }

    public TextoRedigido comAssunto(String novo, List<String> novosAvisos) {
        return new TextoRedigido(versao, novo, blocos, base, telaFuncionalidade, novosAvisos, variantes);
    }

    /** Os avisos de agora mais estes (no fim, sem repetir). */
    public TextoRedigido comMaisAvisos(List<String> mais) {
        List<String> todos = new java.util.ArrayList<>(avisos);
        mais.stream().filter(a -> !todos.contains(a)).forEach(todos::add);
        return new TextoRedigido(versao, assunto, blocos, base, telaFuncionalidade, todos, variantes);
    }

    public TextoRedigido comBlocos(List<BlocoDoTexto> novos) {
        return new TextoRedigido(versao, assunto, novos, base, telaFuncionalidade, avisos, variantes);
    }

    public TextoRedigido comTela(String tela) {
        return new TextoRedigido(versao, assunto, blocos, base, tela, avisos, variantes);
    }
}
