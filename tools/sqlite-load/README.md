# Ensaio de carga SQLite

Execute em PowerShell, com Java 21 e Maven disponíveis:

```powershell
./tools/sqlite-load/run.ps1 -Profile both
```

O script cria arquivos novos dentro de `.runtime-test`, não acessa MySQL e não
altera configurações do plugin nem entregas. Não reutiliza um banco existente.
As duas bases são executadas sequencialmente, para não disputar recursos entre si.

Usa as classes reais `DatabaseManager`, `DatabaseExecutor`, storages e managers.
Somente o contêiner Paper e o encaminhamento para instrumentar a fila usam Mockito,
que já é dependência de testes. Não há alterações na implementação para o ensaio.

Perfis:

- `small`: 10.001 identidades, 1.000 bans, 500 mutes, 110.000 eventos de histórico.
- `large`: 100.001 identidades, 20.000 bans, 10.000 mutes, 1.100.000 eventos.
- Um jogador concentra 10.000 ou 100.000 eventos, para avaliar páginas distantes.

Registra execução SQL, espera na fila, tempo total de cada tarefa, percentis,
limite do cache de nomes, heap da JVM e resultados dos logins modelados. O fluxo
de entrada executa pré-login, recheca o ban, carrega mute e grava a identidade.
10% das tentativas devem ser recusadas por ban. Timeout do pré-login: 15 segundos.
O tempo do fluxo completo inclui a gravação da identidade, que no servidor real
não precisa terminar para o cliente entrar no mundo.

O perfil grande faz também uma cópia do banco em uma pasta nova para um teste
de limite: 650 consultas às últimas páginas do histórico, seguidas de 100 logins.
Os timeouts desse cenário são registrados como resultados, sem alterar o limite
de 15 segundos para esconder recusas.

O modo `large` também mantém uma transação externa de escrita por 18 segundos,
para testar contenção e recusa por timeout. Essa é uma falha induzida, não uma
medida da capacidade normal do plugin. Executa `integrity_check` ao final.

As métricas de heap incluem JVM, Mockito e instrumentação; não representam
isoladamente a memória do plugin. Os arquivos permanecem para inspeção, com um
JSON por cenário e `results.json` contendo o conjunto. O SO pode manter páginas
do banco em cache; este ensaio não força leitura de disco frio.

Esse harness testa armazenamento e fila, não autenticação, pacotes, TPS nem
renderização Minecraft. A validação de clientes reais no Paper é complementar.

## Paper com clientes de protocolo

Também estão incluídos um probe de métricas, preparação de servidor isolado e o
orquestrador de clientes. Requerem Python, Node.js e `minecraft-protocol` disponível
em `.runtime-test/node_modules`. São ferramentas de teste, não dependências do plugin.

Depois do perfil grande, compile o probe com o classpath já gerado:

```powershell
$run = 'CAMINHO_DO_RESULTADO'
$cp = "target/classes;" + (Get-Content "$run/classpath.txt" -Raw).Trim()
javac -proc:none -cp $cp -d "$run/probe-classes" tools/sqlite-load/SqlitePaperProbe.java
python tools/sqlite-load/prepare-paper.py --template CAMINHO_PAPER_1_21_11 --seed "$run/large/bancinematic.db" --probe-classes "$run/probe-classes" --output "$run/paper"
node tools/sqlite-load/paper-load.cjs "$run/paper"
```

O template deve ser Paper 1.21.11 já preparado e parado, com EULA aceita. O script copia
seus arquivos para uma pasta nova, usa somente `127.0.0.1:25585` e não altera o
template. Cinemática desligada, mundo copiado, creative, view/simulation-distance 3,
throttle de conexão por IP desligado para permitir bots no mesmo computador.
Copiar o banco usa o backup SQLite para incluir dados de WAL pendentes.

O orquestrador usa Java no PATH ou `BCLOAD_JAVA`, 2 CPUs visíveis e heap de 1,5 GiB.
Executa 20, 100 e 200 tentativas, com 10% de banidos e 20% de mutados. As consultas
administrativas são chamadas pelas APIs reais de armazenamento: não incluem o custo
de digitar comandos e renderizar todas as mensagens para staff. Mede eventos reais
de pré-login, tempo até o mute estar pronto, fila, heap e médias de tick do Paper.
Encerra o servidor ao terminar e grava `paper-results.json` e `paper-load.log`.
