# 署名と公開の運用

## 専用の署名鍵

今回のアプリ専用にRSA 3072bitのリリース鍵を作成するスクリプトを用意しています。既存アプリの鍵を使いません。パスワードは暗号学的な乱数から生成し、keytoolへ環境変数経由で渡します。

```powershell
python tools/create_signing_key.py
.\gradlew.bat :app:assembleRelease
```

すでに`.local/release.p12`と`.local/signing.properties`が存在する場合、スクリプトは上書きを拒否します。この2ファイルを暗号化した安全な場所へ必ずバックアップしてください。秘密鍵やパスワードを失うと、現在の同一パッケージをそのまま更新できません。公開Gitへ追加しないでください。`.local/`全体をgitignoreし、Windowsでは作成時に現在のユーザーだけへファイルアクセスを制限します。パスワードの表示を要求する操作は行いません。

リリース署名が未設定なら正式リリース処理を完了させない設計です。デバッグ鍵へ自動で切り替えません。

## 初回のGitHub設定

公開リポジトリ作成・正式公開・署名Secrets登録には所有者の明示的な承認が必要です。既存リポジトリへ書き込みません。専用リポジトリを作成した後、その実在するリポジトリに合わせて`distribution.properties`の3項目を設定します。

| キー | 値 |
| --- | --- |
| repository | 作成済みの`所有者/リポジトリ名` |
| websiteUrl | そのリポジトリのGitHub PagesのHTTPS URL（末尾`/`） |
| metadataUrl | websiteUrl + `version.json` |

GitHubのSettings → Pages → Build and deploymentは「GitHub Actions」を選びます。production環境に承認者を設定すると、公開の追加ゲートを作れます（契約・リポジトリ可視性の対応範囲に従ってください）。

必要なGitHub Secrets：

- RELEASE_KEYSTORE_BASE64
- RELEASE_STORE_PASSWORD
- RELEASE_KEY_ALIAS
- RELEASE_KEY_PASSWORD
- RELEASE_CERT_SHA256（公開情報ですが整合性を保つため同じ管理経路を使用）

承認済みの場合、`python tools/configure_github_secrets.py --repository 所有者/リポジトリ名`で値を標準入力からGitHub CLIへ渡せます。値は画面やコマンド引数に表示しません。これはGitHubへ秘密鍵を預ける操作なので、承認がない状態では実行しません。

## 毎回の公開

1. コードを修正し、coreテスト・Android Lint・ビルド・必要な実機確認を行います。
2. `version.properties`のversionCodeを必ず増やし、表示用versionNameを変更します。`release-notes.txt`を更新します。
3. ソースをmainへコミット・pushします。この操作だけでAPKやサイトは公開されません。
4. GitHubのActions → **Publish signed APK and download site** → Run workflowを開き、confirmationへ`PUBLISH`と入力します。
5. ワークフローが署名APKをビルドし、アプリID・デバッグ不可・証明書・バージョンを照合します。SHA-256、サイズ、version.json、history.jsonを同じAPKから生成します。
6. Draft Releaseへ全資産を送り、GitHubから取得し直してハッシュを比較した後に公開します。その後Pagesへ一式をデプロイします。
7. 公開後、実際のversion.jsonとAPK全体を再ダウンロードして一致を確認します。verifyジョブが成功したことを確認してください。

Pages公開に失敗しても旧サイト・旧フィードは維持され、更新前のアプリを削除しません。Release公開後にPagesだけ失敗した場合は、同じバージョンを上書きせず、Actionsの失敗したdeploy/verifyジョブだけを再実行します。buildを含めて再実行すると既存Releaseの上書き防止により停止します。

古い公開履歴を取得できないときは、初回リリース以外で履歴を空にして続行しません。履歴を復旧してから再開します。既存タグや公開Releaseを自動削除する処理はありません。

## ローカルで配布物を作る

実在する配布設定を行い、署名リリースAPKをビルドしてから実行します。

```powershell
python tools/package_release.py
```

`dist/site/`にサイトとversion.json・history.json、`dist/release/`にAPKと同じversion.jsonを作ります。2回目以降は`--history 既存history.jsonの保存先`を指定してください。署名情報や架空のURLでは配布物生成を完了させません。

## アプリ利用者の更新操作

アプリの「今すぐ更新を確認」→「更新APKをダウンロード」→「検証済みAPKをインストール」と進みます。自動ダウンロードを有効にした場合は最初の2段階がバックグラウンドで行われます。通知の許可がない端末ではアプリを開いて確認できます。

必要ならAndroidの「この提供元を許可」を有効にし、戻って標準インストーラーで確認します。ユーザー確認やSamsungの制限を回避しません。更新は現在のアプリへ重ねて行い、アンインストールしません。入力中の文章を失わないよう、先に入力を確定してから更新してください。
