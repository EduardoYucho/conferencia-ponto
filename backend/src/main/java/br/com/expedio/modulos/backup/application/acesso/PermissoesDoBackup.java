package br.com.expedio.modulos.backup.application.acesso;

import br.com.expedio.domain.acesso.FonteDePermissoes;
import br.com.expedio.domain.acesso.Permissao;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * As permissões dos backups de clientes, que aparecem na tela de perfis do ponto (grupo "Suporte"). Toda rota da tela
 * exige {@link #COLETAR}; apagar um backup exige {@link #APAGAR}. A página de coleta não usa login: vale o link.
 */
@Component
public class PermissoesDoBackup implements FonteDePermissoes {

    /** Cadastra clientes, gera links de coleta, acompanha, confere, extrai e baixa os backups. */
    public static final String COLETAR = "backup.coletar";
    /** Apaga backups recebidos (inclui coletar). */
    public static final String APAGAR = "backup.apagar";

    @Override
    public String grupo() {
        return "Suporte";
    }

    @Override
    public int ordem() {
        return 41;
    }

    @Override
    public List<Permissao> permissoes() {
        return List.of(
                Permissao.de(COLETAR, "Coletar backups de clientes",
                        "Cadastra clientes, gera o link de coleta, acompanha o envio, confere, extrai e baixa os backups."),
                Permissao.de(APAGAR, "Apagar backups de clientes",
                        "Apaga backups já recebidos (o arquivo .tar.zst e o relatório).").requer(COLETAR));
    }
}
