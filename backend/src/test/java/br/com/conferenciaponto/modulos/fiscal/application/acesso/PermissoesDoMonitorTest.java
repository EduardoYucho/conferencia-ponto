package br.com.conferenciaponto.modulos.fiscal.application.acesso;

import br.com.conferenciaponto.domain.acesso.Permissao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissoesDoMonitorTest {

    @Test
    void verEConfigurarNoGrupoSuporte() {
        PermissoesDoMonitor permissoes = new PermissoesDoMonitor();
        assertThat(permissoes.grupo()).isEqualTo("Suporte");
        assertThat(permissoes.permissoes()).extracting(Permissao::codigo).containsExactly("fiscal.ver", "fiscal.configurar");
        assertThat(permissoes.permissoes().get(1).requer()).isEqualTo("fiscal.ver");
        assertThat(permissoes.permissoes().get(0).nome()).as("o nome usado no histórico da V18").isEqualTo("Ver o monitor fiscal");
    }
}
