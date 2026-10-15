# Profundo

Live wallpaper Android: escolhes uma foto, a app recorta o sujeito (ML Kit, no telemóvel)
e desenha o relógio **entre o fundo e o sujeito** — o efeito de profundidade do iPhone.

## Fase 1 (esta versão)
- Recorte automático do sujeito
- Posição automática do relógio (sujeito tapa ~25% dos dígitos) + ajuste manual
- Relógio só no ecrã de bloqueio (opção para mostrar também no inicial)

## Preparar o Samsung
1. Good Lock → LockStar → relógio personalizado só com espaços (esconde o relógio da Samsung).
2. Abre o Profundo → escolhe foto → **Aplicar como fundo** → aplica ao bloqueio e ao inicial.

## Limitações conhecidas
- O AOD não mostra live wallpapers — o relógio não aparece lá.
- Na primeira utilização o modelo de recorte pode ainda estar a descarregar (Play Services).

## Build
Cada push corre o GitHub Actions e gera `app-debug.apk` nos artifacts do workflow.
