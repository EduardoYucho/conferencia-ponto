package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.infrastructure.log.RegistroDeLogs;

import java.util.List;

/**
 * @param ultima sequência da linha mais recente do sistema (a próxima consulta pede "depois" dela)
 * @param linhas linhas que atendem ao filtro, da mais antiga para a mais nova
 */
public record LogAoVivoResponse(long ultima, List<RegistroDeLogs.Linha> linhas) {
}
