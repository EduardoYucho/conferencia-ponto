package br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessosABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Os repositórios JDBC da base no PostgreSQL temporário, para os testes dos casos de uso (de outros pacotes). */
public final class RepositoriosDaBase {

    private RepositoriosDaBase() {
    }

    public static AcessosABase acessos() {
        return new AcessosABaseJdbc(PostgresDeTeste.jdbc());
    }

    public static Registros registros() {
        return new RegistrosJdbc(PostgresDeTeste.jdbc(), transacao());
    }

    public static TransactionOperations transacao() {
        return new TransactionTemplate(new DataSourceTransactionManager(PostgresDeTeste.banco()));
    }
}
