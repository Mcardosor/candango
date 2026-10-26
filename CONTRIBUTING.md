# Como contribuir com o Candango

Obrigado pelo interesse! Toda ajuda é bem-vinda: relatar um horário errado, sugerir uma ideia ou mandar código.

## Relatar um problema ou sugerir algo

Abra uma [issue](https://github.com/Mcardosor/candango/issues/new/choose) e escolha o modelo que se encaixa melhor. Para problemas de horário ou de rota, ajuda muito informar:

- **Partida e destino:** de onde para onde (endereço ou código da parada).
- **Dia e horário** em que você consultou.
- **O que o app mostrou** e **o que aconteceu de verdade**, por exemplo a linha que passou ou o horário real.

## Mandar código

1. Faça um fork e crie uma branch a partir da `main` (ex.: `melhora-busca`).
2. Rode os testes antes de abrir o pull request:
   ```bash
   ./gradlew testDebugUnitTest
   ```
3. Se mexer no planejador (`Planner.kt`), adicione ou ajuste um teste em `PlannerTest.kt`. Os testes usam os dados reais de `assets/dados.json`.
4. Se mexer na interface, inclua um print no pull request.
5. Escreva as mensagens de commit em português, curtas e no imperativo (ex.: "Corrige horário de linhas circulares").

## Atualizar os dados da SEMOB

Linhas, paradas e horários mudam de tempos em tempos. Para atualizar:

```bash
node tools/build-data.mjs
./gradlew testDebugUnitTest
```

Faça o commit do `dados.json` gerado num pull request separado, com o título "Atualiza dados da SEMOB (AAAA-MM-DD)".

## Código de conduta

Seja gentil e respeitoso. Críticas ao código são bem-vindas, ataques a pessoas não.
