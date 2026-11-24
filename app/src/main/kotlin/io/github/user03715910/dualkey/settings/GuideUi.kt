package io.github.user03715910.dualkey.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import io.github.user03715910.dualkey.R

internal enum class GuideTopic(
    val title: String,
    val summary: String,
    val image: Int,
    val caption: String,
    val instructions: List<String>,
) {
    SETUP(
        "はじめての設定", "キーボードを有効にして、アプリ内で試す",
        R.drawable.guide_setup, "ホームの「キーボードの準備」",
        listOf(
            "ホームの「1. キーボードを有効にする」を押し、Androidの設定で「DualKey IME」をオンにします。",
            "アプリに戻り、「2. 使用するキーボードを選択」で「DualKey IME」を選びます。",
            "ホームの「ここをタップして入力」で試せます。別のアプリでも、文字入力欄を開くと使えます。",
        ),
    ),
    TYPING(
        "文字の入力", "日本語・英語、フリック・QWERTY、サイズ調整",
        R.drawable.guide_keyboard, "画面キーボードの日本語フリック配列",
        listOf(
            "日本語フリックは「あ」をタップすると「あ」、左・上・右・下に払うと「い・う・え・お」です。「小゛゜」で小文字・濁点・半濁点を切り替えます。",
            "下段の「abc／日本語」で日英を切り替えます。「設定 → 入力とサイズ」で日本語・英語それぞれの配列を選べます。キーの高さ、数字行、振動や操作音も調整できます。",
            "英語のShiftは、1回で次の1文字を大文字にし、素早く2回押すと大文字に固定します。もう一度押すと解除できます。",
            "上段の「縮小」で省スペース表示、「全表示」で通常の大きさへ戻せます。",
        ),
    ),
    CONVERSION(
        "変換と候補一覧", "漢字・予測候補・カナ／英・選択範囲の再変換",
        R.drawable.guide_candidates, "候補一覧の下部にある「すべて」「絞り込み」「絵文字」「顔文字」",
        listOf(
            "「きょう」と入力し、「空白」（QWERTYでは「空白/変換」）を押すと「今日」などの候補が出ます。候補をタップするか、↵で確定します。",
            "「一覧」で候補を広げられます。「絞り込み」は入力した読みに直接対応する候補を中心に表示します。「にこ」などを入力して「絵文字」「顔文字」も試せます。",
            "確定前に「カナ/英」を押すと、カタカナ、意味に対応する英語、ひらがなの順に切り替わります。例えば「いす → イス → chair → いす」です。辞書にない表現の英語化には翻訳モデルが必要です。",
            "確定した日本語を入力欄で範囲選択すると、対応する欄では選んだ部分の変換候補が出ます。物理キーボードでは範囲選択してSpaceを押します。",
        ),
    ),
    EDITING(
        "削除と記号", "左フリックで前を一括削除、記号一覧から入力",
        R.drawable.guide_symbols, "キーボード上段の「記号一覧」を開いた画面",
        listOf(
            "⌫はタップで1文字削除、長押しで未確定の文字や直前の文のまとまりを削除します。範囲選択中の長押しは選択部分を削除します。",
            "⌫を左にフリックすると、カーソルより前を一括削除します。範囲選択中は選択の先頭より前を削除し、選択部分と後ろは残ります。",
            "「記号」で記号配列に切り替えます。「#+=」と「123」でページを移動できます。上段の「記号一覧」では、矢印や数学記号などを分類から探せます。",
            "「おんぷ → ♪」のように読みからも変換できます。「かっこ」で「()」などの括弧の組を選ぶと、対応する入力欄ではカーソルが内側に入ります。",
        ),
    ),
    CLIPBOARD(
        "貼り付けと履歴", "コピーした文章・画像・スクショを再利用する",
        R.drawable.guide_clipboard, "「貼り付け」を長押しすると開く履歴",
        listOf(
            "「貼り付け」をタップすると最新のコピーを貼り付けます。画像のプレビューが出ていれば、そこからも貼り付けられます。",
            "「貼り付け」を長押しすると履歴が開きます。保存するには「クリップボード履歴を保存」をオンにしてください。各項目をタップして貼り付け、「固定」で期限を超えて残せます。",
            "「設定 → 入力とサイズ」で保存件数や画像・動画の保存期間を調整できます。履歴が不要になったときは、一覧から個別削除や「全削除」ができます。",
            "「撮ったスクショを候補に出す」を設定すると、写真へのアクセスを許可した端末で、撮影直後に候補を表示します。履歴へ登録されたスクショを使うには、端末に元の画像が必要です。",
            "画像・GIF・動画は、その形式を受け取れる入力欄へ貼り付けられます。対応していない場合は、入力先アプリの添付ボタンを使ってください。",
        ),
    ),
    HARDWARE(
        "物理キーボード", "Bluetooth・USB、画面表示と変換の操作",
        R.drawable.guide_hardware, "画面キーボードを隠した状態でも表示できる変換候補",
        listOf(
            "BluetoothやUSBで接続すると、ローマ字の日本語入力と英語入力を使えます。「設定 → 物理キーボード」で接続時の表示を選べます。",
            "Spaceまたは変換キーで候補を開き、矢印やSpaceで選んでEnterで確定します。F6は候補の種類の切り替え、Escは変換の取り消しです。",
            "表示切替ショートカットをオンにすると、初期設定のCtrl＋Alt＋Kで画面キーボードを表示・非表示にできます。キーの組合せは変更できます。",
            "「表示切替の通知を使う」をオンにしてAndroidで通知を許可すると、物理キーボード接続中に画面を隠した際、通知から表示できます。ホームの「画面キーボードを表示」でも戻せます。",
        ),
    ),
    TRANSLATION(
        "翻訳", "入力中や選択した文章を日英翻訳する",
        R.drawable.guide_translate, "キーボードの「翻訳」で方向を選ぶ画面",
        listOf(
            "「設定 → 翻訳」でモデルをダウンロードします。初回はWi-Fi接続が必要です。取得後はオフラインで翻訳できます。",
            "未確定の文章を入力するか、入力欄で文章を選択し、キーボードの「翻訳」を押します。",
            "「Googleで日→英」または「Googleで英→日」を選びます。結果を確認してから、入力欄に反映できます。",
        ),
    ),
    DICTIONARY(
        "変換の学習とユーザー辞書", "よく使う表記や名前を出しやすくする",
        R.drawable.guide_dictionary, "設定の「変換と辞書」にあるユーザー辞書",
        listOf(
            "「設定 → 変換と辞書」で、日英の予測候補、絵文字・顔文字の候補をそれぞれ調整できます。",
            "「よく使う日本語の変換を学習」をオンにすると、選んで確定した候補が次回の変換に利用されます。学習データは端末内に保存され、同じ設定画面で削除できます。",
            "変換したい名前や専門用語があるときは、「ユーザー辞書 → 単語を追加」で読みと表示したい表記を登録します。登録済みの単語は編集・削除できます。",
        ),
    ),
    UPDATES(
        "アプリの更新", "新しいバージョンを確認してインストールする",
        R.drawable.guide_updates, "「更新」タブの「自動確認とダウンロード」",
        listOf(
            "アプリ下部の「更新」から新しいバージョンを確認できます。更新が表示されたら、案内に沿ってダウンロードし、Androidの画面でインストールします。",
            "「自動確認とダウンロード」で、起動時の確認、自動ダウンロード、Wi-Fiのみ、通知を設定できます。",
        ),
    ),
}

internal class GuideUi(private val context: Context, private val ui: SettingsUi) {
    fun contents(parent: LinearLayout, open: (GuideTopic) -> Unit) {
        GuideTopic.entries.forEach { topic ->
            ui.card(parent, "${topic.title}  ›", topic.summary).apply {
                tag="guide_${topic.name.lowercase()}"
                isFocusable=true
                minimumHeight=ui.dp(72)
                background=RippleDrawable(ColorStateList.valueOf(0x22087F8C), ui.shape(ui.surface), null)
                setOnClickListener { open(topic) }
                ViewCompat.setScreenReaderFocusable(this, true)
            }
        }
    }

    fun article(parent: LinearLayout, topic: GuideTopic) {
        val card=ui.card(parent, topic.title)
        ViewCompat.setAccessibilityHeading(card.getChildAt(0), true)
        card.addView(ImageView(context).apply {
            setImageResource(topic.image)
            contentDescription=topic.caption
            adjustViewBounds=true
            maxWidth=ui.dp(520)
            scaleType=ImageView.ScaleType.FIT_CENTER
            layoutParams=LinearLayout.LayoutParams(-2,-2).apply {
                gravity=Gravity.CENTER_HORIZONTAL
                topMargin=ui.dp(10)
            }
        })
        card.addView(ui.label(topic.caption, 13f, secondary=true).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        topic.instructions.forEachIndexed { index, instruction ->
            card.addView(ui.label("${index+1}. $instruction").apply {
                setPadding(0, ui.dp(9), 0, ui.dp(7))
            })
        }
    }
}
