package br.com.conferenciaponto.modulos.fiscal.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** O registro do monitor no PostgreSQL de teste, com o schema fiscal limpo. */
public final class RegistroDeTeste {

    private RegistroDeTeste() {
    }

    public static RegistroDoMonitorJdbc limpo() {
        PostgresDeTeste.jdbc().sql("""
                TRUNCATE fiscal.situacao_atual, fiscal.mudanca, fiscal.incidente, fiscal.resumo_hora, fiscal.rodada,
                         fiscal.fonte, fiscal.aviso""").update();
        return new RegistroDoMonitorJdbc(PostgresDeTeste.jdbc(),
                new TransactionTemplate(new DataSourceTransactionManager(PostgresDeTeste.banco())));
    }
}
