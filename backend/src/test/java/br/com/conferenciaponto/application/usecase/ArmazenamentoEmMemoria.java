package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.port.ArmazenamentoComprovantes;

import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class ArmazenamentoEmMemoria implements ArmazenamentoComprovantes {

    final Map<String, byte[]> arquivos = new HashMap<>();

    @Override
    public String armazenar(UUID id, byte[] conteudo, LocalDate dataReferencia) {
        String caminho = "%d/%02d/comprovante_%s.pdf".formatted(dataReferencia.getYear(),
                dataReferencia.getMonthValue(), id);
        arquivos.put(caminho, conteudo.clone());
        return caminho;
    }

    @Override
    public byte[] ler(String caminhoArquivo) throws IOException {
        byte[] conteudo = arquivos.get(caminhoArquivo);
        if (conteudo == null) {
            throw new NoSuchFileException(caminhoArquivo);
        }
        return conteudo.clone();
    }

    /** Os que foram para os desfeitos (caminho de antes). */
    final java.util.List<String> desfeitos = new java.util.ArrayList<>();

    @Override
    public void guardarComoDesfeito(String caminhoArquivo) {
        byte[] conteudo = arquivos.remove(caminhoArquivo);
        if (conteudo != null) {
            arquivos.put("desfeitos/" + caminhoArquivo, conteudo);
        }
        desfeitos.add(caminhoArquivo);
    }

    @Override
    public URI uriDeAcesso(UUID id) {
        return URI.create("/api/comprovantes/" + id + "/download");
    }
}
