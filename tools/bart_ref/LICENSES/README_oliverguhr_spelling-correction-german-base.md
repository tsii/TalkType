---
license: apache-2.0
tags:
- generated_from_trainer
model-index:
- name: bart-base-spelling-de
  results: []
widget:
- text: "correct: ein dransformer isd ein mthode mit der ein compuder eine volge von zeichn übersetz"
  example_title: "1"
- text: "correct: Dresten ist di Landeshaubtstadt des Freistaats Saksens und die zweid größte stadt des landel"
  example_title: "2"
---



## Model description

This is a proof of concept spelling correction model for german. The model should fix your typos and punctuation.
If you like to participate in the development or run your own experiments, have a look at [the GitHub repo](https://github.com/oliverguhr/spelling).


Model Input:

> ein dransformer isd ein mthode mit der ein compuder eine volge von zeichn in eine andrere folge von zeichen übersetzn kann dies kan zb genutzt werdne um text von einer spracge in eine andrere zu übersetzen

Model Output:

> Ein Transformer ist eine Methode, mit der ein Computer eine Folge von Zeichen in eine andere Folge von Zeichen übersetzen kann dies kann z.B. genutzt werden, um Texte von einer Sprache in eine andere zu übersetzen.


## Intended uses & limitations

This is a work in progress, be aware that the model can produce artefacts. 
You can test the model using the pipeline-interface:

```python
from transformers import pipeline

fix_spelling = pipeline("text2text-generation",model="oliverguhr/spelling-correction-german-base")

print(fix_spelling("correct: das idst ein neuZr test",max_length=256))
```
