# Thunder Radar — Minecraft Java 26.3 / Fabric

Mod client-side para singleplayer. Exibe uma estimativa estatística de quando uma
tempestade com chuva + trovões poderá acontecer.

## O que faz
- Lê os temporizadores vanilla de chuva e trovão do servidor integrado.
- Faz 5.000 simulações estatísticas locais para estimar a mediana da próxima tempestade.
- Mostra chances estimadas para 1 hora e 2 horas de jogo.
- Avisa no chat quando a tempestade começa.
- Não usa comandos, não altera `WeatherData`, não escreve temporizadores e não muda regras do mundo.

## Limitações importantes
A previsão é probabilística, não uma contagem regressiva garantida. O jogo sorteia
novas durações quando cada contador termina. A estimativa usa os intervalos de
duração vanilla conhecidos e pode divergir caso outro mod/datapack altere o clima.
A HUD só aparece no singleplayer (servidor integrado).

## Requisitos para compilar
- Minecraft Java 26.3
- Fabric Loader 0.20.1 ou superior
- Fabric API 0.165.0+26.3
- Java JDK 25
- Gradle 9.7.1 (ou uma versão compatível com Fabric Loom 1.19-SNAPSHOT)

## Compilar no Windows
1. Instale o JDK 25 e confirme no Prompt de Comando:
   `java -version`
2. Instale Gradle 9.7.1 e confirme:
   `gradle -v`
3. Extraia esta pasta.
4. Abra o Prompt de Comando dentro da pasta `ThunderRadar-26.3`.
5. Execute:
   `gradle build`
6. O arquivo gerado ficará em:
   `build/libs/thunder-radar-1.0.0.jar`

## Instalar no Prism Launcher
1. Abra a instância Minecraft 26.3 Fabric.
2. Clique em `Editar instância` > `Abrir pasta`.
3. Abra a pasta `mods`.
4. Copie `thunder-radar-1.0.0.jar` para ela.
5. Inicie o jogo em singleplayer.

Faça backup do mundo antes de testar qualquer mod novo.
