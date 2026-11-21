package br.com.expedio.modulos.conhecimento.infrastructure.persistence;

import br.com.expedio.modulos.PostgresDeTeste;
import br.com.expedio.modulos.conhecimento.domain.registro.Registros;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Os repositórios JDBC da base no PostgreSQL temporário, para os testes dos casos de uso (de outros pacotes). */
public final class RepositoriosDaBase {

    private RepositoriosDaBase() {
    }

    public static Registros registros() {
        return new RegistrosJdbc(PostgresDeTeste.jdbc(), transacao());
    }

    public static br.com.expedio.modulos.conhecimento.domain.vetor.Vetores vetores() {
        return new VetoresJdbc(PostgresDeTeste.jdbc(), transacao());
    }

    public static TransactionOperations transacao() {
        return new TransactionTemplate(new DataSourceTransactionManager(PostgresDeTeste.banco()));
    }
}
