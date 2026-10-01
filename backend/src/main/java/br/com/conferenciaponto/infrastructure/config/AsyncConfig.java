package br.com.conferenciaponto.infrastructure.config;

import br.com.conferenciaponto.infrastructure.importacao.GerenciadorMonitoresPdf;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Threads de segundo plano (os monitores de PDFs têm threads próprias: ver GerenciadorMonitoresPdf).
 * {@code @EnableScheduling} habilita o heartbeat das conexões SSE.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String EXECUTOR_REPROCESSAMENTO = "reprocessamentoExecutor";
    public static final String EXECUTOR_CONCILIACAO = "conciliacaoExecutor";

    /**
     * Conciliação com o RH (conferir um relatório recém-enviado, reconferir um dia que mudou): uma thread
     * só, em fila, para as conferências não disputarem a mesma divergência.
     */
    @Bean(name = EXECUTOR_CONCILIACAO)
    public ThreadPoolTaskExecutor conciliacaoExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(5_000);
        executor.setThreadNamePrefix("conciliacao-");
        executor.setDaemon(true);
        return executor;
    }

    /** Reprocessamento manual da pasta (POST /api/v1/importacoes/reprocessar), fora da thread HTTP. */
    @Bean(name = EXECUTOR_REPROCESSAMENTO)
    public ThreadPoolTaskExecutor reprocessamentoExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("reprocessamento-");
        executor.setDaemon(true);
        return executor;
    }
}
