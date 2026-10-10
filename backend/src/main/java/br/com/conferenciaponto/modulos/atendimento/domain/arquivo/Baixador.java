package br.com.conferenciaponto.modulos.atendimento.domain.arquivo;

import java.nio.file.Path;

/** Baixa um anexo pelo link assinado do Digisac. */
public interface Baixador {

    /**
     * Baixa para {@code parcial}, continuando de onde parou se o arquivo já tiver uma parte (pedido parcial).
     *
     * @param limite tamanho máximo em bytes
     * @return o tamanho final
     * @throws FalhaNoDownload com a mensagem para a pessoa e se vale tentar de novo
     */
    long baixar(String url, Path parcial, long limite);
}
