# Profundo

Live wallpaper Android: escolhes uma foto, a app recorta o sujeito (ML Kit, no telemóvel)
e desenha o relógio **entre o fundo e o sujeito** — o efeito de profundidade do iPhone.

## Fase 1 (esta versão)
- Recorte automático do sujeito
- Posição automática do relógio (sujeito tapa ~25% dos dígitos) + ajuste manual
- Relógio só no ecrã de bloqueio (opção para mostrar também no inicial)
- Paralaxe em 3 camadas: fundo (longe), relógio (meio) e sujeito (perto) a velocidades diferentes;
  a zona do sujeito no fundo é preenchida com as cores à volta para não aparecer o "fantasma"
- No vídeo a imagem ainda se move em bloco

## Relógio
- Estilos: horizontal, vertical (horas sobre minutos) e analógico
- Letras: moderno, fino, condensado, clássico, mono; contorno, sombra, opacidade, data acima/abaixo/oculta
- 12 cores, tom livre e cor automática tirada da foto
- Opção "relógio à frente do sujeito" para fotos em que ficaria tapado

## Efeito 3D
- Profundidade por IA (Depth Anything V2 Small) e malha deformada
- Ponto fixo (relógio / sujeito / fundo), intensidade, suavidade das bordas, inverter, movimento automático

## Vídeo (fase 2)
- Escolhe um vídeo (usa os primeiros 4 s, até 30 fps)
- Profundidade em 1 de cada 3 frames, interpolada nos restantes
- Cada frame é recortado uma vez ao importar; a máscara é suavizada no tempo para não tremer
- No bloqueio só reproduz: frame → relógio → frame cortado pela máscara

## Preparar o Samsung
1. Good Lock → LockStar → relógio personalizado só com espaços (esconde o relógio da Samsung).
2. Abre o Profundo → escolhe foto → **Aplicar como fundo** → aplica ao bloqueio e ao inicial.

## Limitações conhecidas
- O AOD não mostra live wallpapers — o relógio não aparece lá.
- Na primeira utilização o modelo de recorte pode ainda estar a descarregar (Play Services).

## Build
Cada push corre o GitHub Actions e gera `app-debug.apk` nos artifacts do workflow.
