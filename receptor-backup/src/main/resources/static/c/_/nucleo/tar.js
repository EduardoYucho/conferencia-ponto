// Cabeçalhos .tar (ustar), com cabeçalho PAX quando o caminho tem acento, passa de 100 bytes ou o arquivo passa de
// 8 GiB. É o formato que o tar do Windows, o 7-Zip e o commons-compress do Receptor leem.

const codificador = new TextEncoder();
const MAX_OCTAL_11 = 0o77777777777; // 8 GiB - 1

function campoTexto(bloco, deslocamento, tamanho, texto) {
  const bytes = codificador.encode(texto);
  bloco.set(bytes.subarray(0, tamanho), deslocamento);
}

function campoOctal(bloco, deslocamento, tamanho, valor) {
  const texto = Math.floor(valor).toString(8).padStart(tamanho - 1, '0');
  campoTexto(bloco, deslocamento, tamanho - 1, texto);
  bloco[deslocamento + tamanho - 1] = 0;
}

function asciiSimples(texto) {
  return /^[\x20-\x7e]*$/.test(texto);
}

function cabecalhoUstar(nome, tamanho, mtimeSegundos, tipo) {
  const bloco = new Uint8Array(512);
  campoTexto(bloco, 0, 100, nome);
  campoOctal(bloco, 100, 8, tipo === '5' ? 0o755 : 0o644);
  campoOctal(bloco, 108, 8, 0);
  campoOctal(bloco, 116, 8, 0);
  campoOctal(bloco, 124, 12, tamanho);
  campoOctal(bloco, 136, 12, mtimeSegundos);
  bloco.fill(0x20, 148, 156); // soma provisória com espaços
  bloco[156] = tipo.charCodeAt(0);
  campoTexto(bloco, 257, 6, 'ustar');
  bloco[262] = 0;
  campoTexto(bloco, 263, 2, '00');
  let soma = 0;
  for (let i = 0; i < 512; i++) soma += bloco[i];
  const texto = soma.toString(8).padStart(6, '0');
  campoTexto(bloco, 148, 6, texto);
  bloco[154] = 0;
  bloco[155] = 0x20;
  return bloco;
}

function registroPax(chave, valor) {
  const corpo = ` ${chave}=${valor}\n`;
  const tamanhoCorpo = codificador.encode(corpo).length;
  let tamanho = tamanhoCorpo + 1;
  while (String(tamanho).length + tamanhoCorpo !== tamanho) tamanho = String(tamanho).length + tamanhoCorpo;
  return `${tamanho}${corpo}`;
}

/** Preenchimento até o próximo múltiplo de 512 depois de {@code tamanho} bytes de dados. */
export function preenchimento(tamanho) {
  const resto = tamanho % 512;
  return resto === 0 ? 0 : 512 - resto;
}

/**
 * Bytes do cabeçalho de uma entrada. {@code caminho} usa "/", sem barra no começo; pasta termina com "/".
 * {@code tipo}: 'arquivo' ou 'pasta'. {@code modificado}: milissegundos.
 */
export function cabecalho(caminho, tipo, tamanho, modificado) {
  const tipoTar = tipo === 'pasta' ? '5' : '0';
  const nome = tipo === 'pasta' && !caminho.endsWith('/') ? caminho + '/' : caminho;
  const mtime = Math.max(0, Math.floor((modificado || 0) / 1000));
  const tamanhoReal = tipo === 'pasta' ? 0 : tamanho;
  const precisaPax = !asciiSimples(nome) || codificador.encode(nome).length > 100 || tamanhoReal > MAX_OCTAL_11;
  if (!precisaPax) return cabecalhoUstar(nome, tamanhoReal, mtime, tipoTar);

  let registros = registroPax('path', nome);
  if (tamanhoReal > MAX_OCTAL_11) registros += registroPax('size', String(tamanhoReal));
  const dadosPax = codificador.encode(registros);
  const curto = nome.replace(/[^\x20-\x7e]/g, '_').slice(-90);
  const blocoPax = cabecalhoUstar(`PaxHeaders/${curto}`.slice(0, 99), dadosPax.length, mtime, 'x');
  const totalPax = 512 + dadosPax.length + preenchimento(dadosPax.length);
  const saida = new Uint8Array(totalPax + 512);
  saida.set(blocoPax, 0);
  saida.set(dadosPax, 512);
  const principal = cabecalhoUstar(curto, tamanhoReal > MAX_OCTAL_11 ? 0 : tamanhoReal, mtime, tipoTar);
  saida.set(principal, totalPax);
  return saida;
}
