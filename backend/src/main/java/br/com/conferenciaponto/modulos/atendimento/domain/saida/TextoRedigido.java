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
 */
public record TextoRedigido(int versao, String assunto, List<BlocoDoTexto> blocos, Map<String, String> base,
                            String telaFuncionalidade, List<String> avisos) {

    public static final int VERSAO = 1;

    public TextoRedigido {
        blocos = List.copyOf(blocos);
        base = base == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(base));
        avisos = List.copyOf(avisos);
    }

    public TextoRedigido comAssunto(String novo, List<String> novosAvisos) {
        return new TextoRedigido(versao, novo, blocos, base, telaFuncionalidade, novosAvisos);
    }
}
