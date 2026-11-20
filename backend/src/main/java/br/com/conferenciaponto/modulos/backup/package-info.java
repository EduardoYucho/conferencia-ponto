/**
 * Backups de clientes: traz para este computador o backup inteiro das pastas do sistema de um cliente (AppSrc, Data com
 * os .DAT/.K*, DDSrc...), enviado pelo Chrome/Edge do cliente por um link, sem instalar nada lá.
 *
 * <p>A tela (menu Suporte) cadastra clientes, gera o link e acompanha. A página de coleta ({@code /c/<token>}) lê as
 * pastas no navegador do cliente, monta o .tar em blocos, calcula o SHA-256 de cada arquivo, compacta com zstd e envia
 * em pedaços que continuam de onde pararam. Os pedaços chegam por uma porta só deste computador que o túnel do
 * cloudflared publica; o backup vira um .tar.zst na pasta configurada (nunca no banco), conferido arquivo a arquivo.
 *
 * <p>Não tem tabelas: o estado de cada coleta fica ao lado dos backups ({@code <pasta>\.coletas}). Não conhece os
 * outros módulos, e eles não o conhecem.
 */
package br.com.conferenciaponto.modulos.backup;
