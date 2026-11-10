/**
 * Monitor fiscal: a situação dos serviços dos documentos fiscais (NF-e, NFC-e, CT-e, MDF-e) por autorizador e por
 * estado, lida a cada 5 minutos dos painéis públicos da SVRS e por teste de conexão nos outros autorizadores, com o
 * histórico (mudanças, incidentes, disponibilidade por hora). Tabelas no schema {@code fiscal}. Não conhece os outros
 * módulos, e eles não o conhecem.
 */
package br.com.conferenciaponto.modulos.fiscal;
