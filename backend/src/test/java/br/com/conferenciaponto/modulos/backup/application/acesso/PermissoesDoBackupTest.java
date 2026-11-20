package br.com.conferenciaponto.modulos.backup.application.acesso;

import br.com.conferenciaponto.domain.acesso.Permissao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissoesDoBackupTest {

    @Test
    void coletarEApagarNoGrupoSuporte() {
        PermissoesDoBackup permissoes = new PermissoesDoBackup();
        assertThat(permissoes.grupo()).isEqualTo("Suporte");
        assertThat(permissoes.permissoes()).extracting(Permissao::codigo).containsExactly("backup.coletar", "backup.apagar");
        assertThat(permissoes.permissoes().get(1).requer()).isEqualTo("backup.coletar");
    }
}
