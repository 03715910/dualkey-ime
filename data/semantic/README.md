# JMdictによる英語・カタカナの追加候補

`JMdict_e.xml.gz`はEDRDGが公開するJMdictの日本語・英語部分の原本です。SHA-256は`7cbacefaf3264a72476327c7bff837348b239ea605ad7818d5cf79c7f5ea9dcb`です。APKには原本を含めず、生成済みの検索用データを含めます。

著作権：James William BREEN and the Electronic Dictionary Research and Development Group。
原本と、`app/src/main/assets/semantic/`にある派生辞書データは**CC BY-SA 4.0**です。独自に書いたアプリのソースコードとはライセンスを区別します。

- 出典：[JMdict](https://www.edrdg.org/jmdict/j_jmdict.html)
- [EDRDGの利用条件](https://www.edrdg.org/edrdg/licence.html)
- [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
- 著作権・変更内容・同梱文書：`app/src/main/assets/semantic/LICENSE-JMDICT.txt`

再生成はプロジェクト直下で`python tools/build_semantic_dictionary.py`を実行します。入力文章を収集・送信する処理はありません。

最初の2つの語義から短い英語を選び、補足説明を除き、同じ第一語義を持つカタカナ語を関連付けています。用例の文脈理解やすべての意味の一致は保証しません。複数の意味がある語では候補を確認してください。
