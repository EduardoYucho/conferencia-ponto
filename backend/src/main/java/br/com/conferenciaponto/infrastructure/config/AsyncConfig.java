package br.com.conferenciaponto.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Threads de segundo plano: o monitor de PDFs roda num executor próprio de 1 thread
 * (o laço do WatchService é bloqueante e não deve ocupar o pool compartilhado).
 * {@code @EnableScheduling} habilita o heartbeat das conexões SSE.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String EXECUTOR_MONITOR_PDF = "monitorPdfExecutor";
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

    @Bean(name = EXECUTOR_MONITOR_PDF)
    public ThreadPoolTaskExecutor monitorPdfExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("monitor-pdf-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
