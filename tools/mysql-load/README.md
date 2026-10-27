# Carga moderada MySQL

Ferramentas de teste, sem mudanças na implementação do plugin e sem novas dependências
de produção. Reutilizam o harness e o probe da pasta `tools/sqlite-load`.

Configure as variáveis de ambiente `BANCINEMATIC_TEST_MYSQL_HOST`,
`BANCINEMATIC_TEST_MYSQL_DATABASE`, `BANCINEMATIC_TEST_MYSQL_USERNAME` e
`BANCINEMATIC_TEST_MYSQL_PASSWORD`. Não coloque a senha em arquivos ou comandos
salvos em scripts. Depois execute, com Java 21 e Maven no PATH:

```powershell
./tools/mysql-load/run.ps1
```

Opcionalmente, acrescente `-PaperTemplate CAMINHO_PAPER_1_21_11` para testar clientes
de protocolo reais. O template deve estar parado e ter EULA aceita. Essa etapa usa
Python, Node.js e `minecraft-protocol` já instalado em `.runtime-test/node_modules`.

O script cria quatro tabelas com prefixo aleatório exclusivo. O arquivo `state.json`
guarda somente o prefixo e os nomes das tabelas, sem senha. A limpeza valida esse
prefixo e remove exclusivamente essas tabelas ao final, inclusive após erro.
Se a rede impedir a limpeza, preserve `state.json` e execute `MysqlVerify` com
`cleanup` quando a conexão voltar. Nunca reutilize esse prefixo para dados reais.

Base: 10.001 identidades do harness, 1.000 bans, 500 mutes e 110.000 eventos de
histórico; mais 40 identidades e pequenas fixtures para o Paper. O carregamento
inicial usa lotes reescritos apenas para preparar dados; as medições usam o driver
e as opções reais do plugin, com uma conexão reutilizada e uma fila serial.

Primeiro mede a latência de rede/SELECT e as operações isoladas. Depois executa
20 segundos com 0,5 entrada e 0,25 operação administrativa por segundo, seguidos
de 20 segundos com 1 entrada e 0,5 operação por segundo. Uma rajada de 10 entradas
só é tentada se o pré-login p95 continuar abaixo de 3 segundos e sem timeouts.

Não há bloqueio induzido, inundação de históricos profundos, rajada de 500 clientes
ou aumento dos timeouts. O Paper usa somente `127.0.0.1:25586`, tenta grupos de 10
e 20 clientes e interrompe o aumento se o pré-login p95 chegar a 5 segundos.
As credenciais do servidor ficam exclusivamente no ambiente do processo Java.

Os tempos incluem a rota até o banco remoto; não devem ser interpretados como
uma comparação isolada entre motores MySQL e SQLite local. Aumente taxas somente
depois de analisar a fila e a latência medidas.
