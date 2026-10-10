package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Os repositórios JDBC do gerador no PostgreSQL temporário, para os testes dos casos de uso (de outros pacotes). */
public final class RepositoriosDeTeste {

    private RepositoriosDeTeste() {
    }

    public static Atendimentos atendimentos() {
        return new AtendimentosJdbc(PostgresDeTeste.jdbc(), new ObjectMapper());
    }

    public static Arquivos arquivos() {
        return new ArquivosJdbc(PostgresDeTeste.jdbc());
    }

    public static Tarefas tarefas() {
        return new TarefasJdbc(PostgresDeTeste.jdbc(), transacao());
    }

    public static TransactionOperations transacao() {
        return new TransactionTemplate(new DataSourceTransactionManager(PostgresDeTeste.banco()));
    }
}
