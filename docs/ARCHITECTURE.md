# 設計・公式仕様の確認

調査日：2026-10-31。既存のBoardDuelとことだまん辞書の配布情報・更新処理は読み取りだけを行いました。既存アプリの署名鍵やパッケージを流用していません。

## 入力の流れ

画面キーと物理キー → `DualKeyService` → `Composer/Romaji` → 交換可能な`ConversionEngine` → `InputConnection`。

ローマ字の未完部分はcomposing spanに保持します。変換中の候補インデックスと、ひらがな原文を分けます。英語予測もIME自身のcomposing spanだけを所有し、候補選択時に無関係な確定済み文章を削除しません。パスワード・数値入力をEditorInfoで判定します。自動学習や入力履歴を保存する処理はありません。

物理接続はInputManagerの非仮想・ALPHABETICなキーボードとConfigurationで検出します。表示状態は`VisibilityController`に集約します。完全非表示では`onEvaluateInputViewShown`と`requestHideSelf`を使い、候補専用の余分な領域も非表示にします。通常表示への復帰ではAPI28以上は`requestShowSelf`、API26–27だけ公式の旧APIを互換処理として使用します。公開API以外には依存しません。[InputMethodService公式](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)

Samsungでの接続通知、ソフトキーボード設定、OSのショートカット先取りは端末ごとの実測が必要です。Ctrl+Alt+Kが常に届くとは仮定せず、アプリ画面とクイック設定タイルの復帰経路を設けています。

## Mozcを今回組み込まなかった理由

[現行のAndroidビルド手順](https://github.com/google/mozc/blob/master/docs/build_mozc_for_android.md)はAndroid用`libmozc.so`のビルドを案内しています。Androidクライアントは廃止され、Linux/macOSでのビルドが検証対象です。Bazelisk、C++、Python 3.12以上、NDK r29などの別ツールチェーンが必要です。現環境のWindows/GradleアプリへAARを追加するだけの導入ではありません。

コードはBSD 3-Clauseが基本ですが、辞書・第三者コードの条件は別々の確認が必要です。[MozcのREADMEとライセンス説明](https://github.com/google/mozc)

今回の優先範囲は入力と安全な配布の成立なので、純Kotlinの簡易辞書を採用しました。JNIアダプター、protobufセッション、辞書データの配置、ABI別ネイティブビルド、ライセンス同梱、非同期候補処理を追加して`ConversionEngine`を差し替える予定です。Mozcの実際の起動時間・変換速度・メモリ・ストレージ増加量は、ビルド未実施のため未計測です。根拠のない容量値や性能値は示しません。

## 翻訳

[ML Kit公式Android手順](https://developers.google.com/ml-kit/language/translation/android)に従い`translate:17.0.3`を使用します。日本語と英語のモデル、非同期Task、モデル削除を翻訳クラスに閉じ込めます。初回のモデルダウンロードは明示操作とWi-Fi条件で行います。別の入力欄・パスワードへ移った場合や文字が変更された場合、古い翻訳結果を挿入しません。

入力テキストの翻訳処理は端末内ですが、SDKの端末・利用状況・診断データの扱いは[Googleの開示](https://developers.google.com/ml-kit/android-data-disclosure)に従って説明します。結果の横には[公式の出典表示バッジ](https://cloud.google.com/translate/attribution)を加工せず使用します。

## 更新と安全境界

更新モジュールはEditorInfo、入力テキスト、InputConnectionを受け取りません。更新通信に文章が入り込む設計を避けています。DataStoreの設定だけが自動確認・ダウンロード・Wi-Fi・通知条件を渡します。

メタデータの信頼に加え、APK全体の署名検証にapksigを使います。メタデータ中のハッシュだけでは、更新サーバーが侵害された際の防御にならないためです。証明書のSHA-256をビルド時に埋め込み、現在のインストール証明書とも完全一致を要求します。ダウンロード先はリポジトリ内の固定命名のRelease資産に制限し、リダイレクトもGitHubの資産用ホストだけに制限します。

アプリ内のキャッシュ領域に一時ファイルを作り、検証後にだけFileProvider経由で標準インストーラーに渡します。失敗した一時ファイルは削除します。古いファイルは次回ダウンロード時に7日経過を目安に削除します。設定は`dualkey_settings_v1`という固定名で保存し、同じ署名の更新では維持します。将来のキー形式変更は新しいスキーマへの明示的なマイグレーションを追加します。

ユーザー確認なしの更新がAndroidの全条件で不可能という意味ではありません。[PackageInstaller.SessionParams公式](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int))では、API31以上でUSER_ACTION_NOT_REQUIRED、OS別のtargetSdk条件、インストール元／更新所有者／自己更新、UPDATE_PACKAGES_WITHOUT_USER_ACTIONなどの条件を定めています。STATUS_PENDING_USER_ACTIONへの対応も必要です。今回はその方式を採用せず、最終確認を標準画面で行います。Samsung実機で未検証のため無確認インストール可能とは報告しません。

## 配布方式と開発者確認

[GitHub Pagesの制限](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits)には公開サイト1GB、月間帯域のソフト上限などがあります。APKの履歴をPagesリポジトリに蓄積せず、Releasesへ置く方針です。ページとメタデータはPagesで配信します。

2026年の開発者確認制度は対象地域・配布元ごとの導入段階があります。公式FAQは2026-09-30の対象を参加ストア・特定地域と説明し、ADBや直接配布に関する区別も案内しています。一律に日本のすべてのAPK配布が禁止されるとは解釈していません。所有者の本人確認やアプリ登録をこの開発作業で完了したとは扱いません。公開運用の前に[Android公式FAQ](https://developer.android.com/developer-verification/guides/faq)と[登録ガイド](https://developer.android.com/developer-verification)を確認してください。
