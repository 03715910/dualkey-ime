# 通知による表示切替と下部バー（0.1.10）

「設定 → 物理キーボード → 通知から切り替え」の「表示切替の通知を使う」をONにします。初期値はONです。Androidの通知を許可し、使用するIMEにDualKeyを選んでいるときだけ、操作用の通知を表示します。「通知の許可・設定」から許可を確認できます。アプリ更新の通知とは別に停止できます。

通知本文または「表示／非表示を切替」を押すと、画面キーボードを切り替えます。通知パネルが残った場合は閉じてください。BroadcastReceiverとInputMethodServiceの公式表示APIで処理し、別のActivityを開きません。現在の入力欄がない場合は選択をDataStoreへ保存し、次に入力欄へフォーカスしたときに適用します。IMEが停止中でも他のアプリの入力欄を強制的に開くことはしません。

通知には入力文章・変換候補・入力先のアプリ名を含めません。別のIMEを選択したときは通知を消します。通知をOFFにすると保留中の通知操作も取り消します。通知が拒否されていても文字入力や物理ショートカットは使えます。ロック中は操作を適用しません。常駐サービスやオーバーレイ権限は追加していません。

## 下部のキーボード切替・下向き矢印のバー

「完全非表示時は下部バーも隠す」の初期値はONです。Android 11（API30）以降の公開API `WindowInsetsController.hide(captionBar())` をIME自身のウィンドウへ適用します。通常表示・省スペース表示では `show(captionBar())` で復帰します。ホーム操作のジェスチャー領域や他のアプリのステータスバーを消す操作は行いません。

Androidのバージョンやメーカーによって、このIME用キャプションバーの制御に対応していない場合があります。API26〜29ではこのAPIを呼びません。非公開のビュー・フィールドの探索やリフレクションで強制削除しません。Samsung実機への適用結果は、エミュレーターの結果と区別して報告します。

候補表示には従来の高さ0のホストとIMEの子ウィンドウを使います。下部へ固定した入力欄で、物理変換中もバーが文字やタッチを覆わないことをテストします。PC風の縦候補は物理キー操作だけで開き、画面フリックと通常の候補一覧は変更しません。

参考：[WindowInsetsController](https://developer.android.com/reference/android/view/WindowInsetsController)、[InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)、[通知の権限](https://developer.android.com/develop/ui/views/notifications/notification-permission)。

## 公開と確認

[0.1.10のダウンロードサイト](https://03715910.github.io/dualkey-ime/)を公開しました。既存アプリの「更新」から確認して更新できます。最終インストールではAndroidの許可・確認を行ってください。

API35の専用エミュレーターで入力・設定55項目、通知権限を拒否した場合の入力、横画面の下端入力欄を確認しました。縦横とも入力欄下端への実タッチが届き、通常表示では下部バーも復帰しました。Samsung実機は未確認です。詳細と途中の失敗・修正は[テスト結果](TEST_RESULTS.md)に記録しています。
