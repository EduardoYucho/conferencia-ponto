package br.com.conferenciaponto.infrastructure.backup;

import java.util.List;

/** Quem faz o backup de fato (no Windows, o {@code ponto backup}); separado para o agendamento ser testável. */
interface ExecutorDoBackup {

    /** O backup foi feito, mas a cópia fora do PC não (código de saída do {@code ponto backup}). */
    int SEM_COPIA_FORA = 3;

    /** @return por que o backup não pode rodar nesta máquina, ou {@code null} se pode */
    String indisponivel();

    /** Faz o backup e espera terminar. Não lança: um problema vira um código diferente de zero com o motivo. */
    Execucao executar(String motivo);

    /**
     * @param codigo 0 = feito; {@link #SEM_COPIA_FORA} = feito, sem a cópia fora do PC; outro = não feito
     * @param linhas o que o comando escreveu (sem senha: o {@code ponto backup} nunca a mostra)
     */
    record Execucao(int codigo, List<String> linhas) {

        public Execucao {
            linhas = List.copyOf(linhas);
        }

        boolean feito() {
            return codigo == 0 || codigo == SEM_COPIA_FORA;
        }

        /** A explicação do comando para o que deu errado, ou a última linha que ele escreveu. */
        String motivo() {
            for (int i = linhas.size() - 1; i >= 0; i--) {
                String linha = linhas.get(i).strip();
                for (String marca : List.of("Backup não feito:", "Cópia fora do PC não feita:")) {
                    int onde = linha.indexOf(marca);
                    if (onde >= 0) {
                        return linha.substring(onde + marca.length()).strip();
                    }
                }
            }
            for (int i = linhas.size() - 1; i >= 0; i--) {
                if (!linhas.get(i).isBlank()) {
                    return linhas.get(i).strip();
                }
            }
            return "o comando terminou com o código " + codigo + ", sem explicação.";
        }
    }
}
