package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.ComprovanteImportado;
import br.com.conferenciaponto.domain.port.ComprovantePontoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Últimos comprovantes processados (auditoria exibida no front-end). */
@Service
@Transactional(readOnly = true)
public class ConsultarImportacoesUseCase {

    private static final int LIMITE_MAXIMO = 100;

    private final ComprovantePontoRepository comprovantes;

    public ConsultarImportacoesUseCase(ComprovantePontoRepository comprovantes) {
        this.comprovantes = comprovantes;
    }

    public List<ComprovanteImportado> recentes(UUID usuarioId, int limite) {
        return comprovantes.recentes(usuarioId, Math.max(1, Math.min(limite, LIMITE_MAXIMO)));
    }
}
