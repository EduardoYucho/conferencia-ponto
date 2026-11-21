package br.com.expedio.infrastructure.web.dto;

/** @param link link da planilha do Google (https://docs.google.com/spreadsheets/d/...) */
public record PlanilhaRequest(String link) {
}
