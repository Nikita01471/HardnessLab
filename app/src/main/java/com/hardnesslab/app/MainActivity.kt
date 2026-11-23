package com.hardnesslab.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.WindowInsets
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.graphics.drawable.GradientDrawable
import android.widget.*
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.content.Context
import kotlin.math.sqrt
import java.util.Locale
import kotlin.math.floor

/** Formulas transcribed from the user's workbook, including microscope conversions. */
object Hardness {
    fun vickers(d1: Double, d2: Double, load: Double, mode: Int, newtons: Boolean): Double {
        require(d1.isFinite() && d2.isFinite() && load.isFinite() && d1 > 0 && d2 > 0 && mode in 0..3)
        val mean = (d1 + d2) / 2
        val diagonalMm = when(mode) { 0 -> mean; 1 -> mean / 1000; 2 -> mean / 2000; else -> mean * 0.3 / 1000 }
        require(diagonalMm > 0 && load > 0)
        return (if(newtons) 0.1891 else 1.854) * load / (diagonalMm * diagonalMm)
    }
    fun brinell(ball: Double, d1: Double, d2: Double, load: Double, newtons: Boolean, microscope100: Boolean): Double {
        val factor = if(microscope100) 0.01 else 1.0
        require(ball.isFinite() && d1.isFinite() && d2.isFinite() && load.isFinite() &&
            d1 * factor > 0 && d1 * factor < ball && d2 * factor > 0 && d2 * factor < ball)
        val d = (d1 + d2) / 2 * factor
        require(ball > 0 && d > 0 && d < ball && load > 0)
        return (if(newtons) 0.102 else 1.0) * 2 * load / (3.14159 * ball * (ball - sqrt(ball * ball - d * d)))
    }
    fun thickness(hv: Double, loadKgf: Double, factor: Double): Double {
        require(hv.isFinite() && loadKgf.isFinite() && factor.isFinite() && hv > 0 && loadKgf > 0 && factor > 0)
        return sqrt(1.854 * loadKgf / hv) * factor
    }
}

/** Lookup data extracted from the cached Excel reference tables. */
class ThicknessTables(activity: Activity) {
    private val data = mutableMapOf<String, List<Pair<Double, Double>>>()
    init {
        activity.assets.open("thickness_tables.tsv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2) data[parts[0]] = parts[1].split(';').mapNotNull { pair ->
                    val v = pair.split(',')
                    if (v.size != 2) null else try { v[0].toDouble() to v[1].toDouble() } catch (_: Exception) { null }
                }
            }
        }
    }
    fun lookup(key: String, hardness: Double, exact: Boolean = false): Double? {
        val values = data[key] ?: return null
        if (exact) return values.firstOrNull { it.first == hardness }?.second
        // Excel VLOOKUP(..., TRUE): largest key not exceeding the query.
        return values.filter { it.first <= hardness }.maxByOrNull { it.first }?.second
    }
}

class MainActivity : Activity() {
    private val bg = Color.rgb(12, 19, 31)
    private val panel = Color.rgb(24, 35, 51)
    private val accent = Color.rgb(75, 196, 235)
    private val successGreen = Color.rgb(34, 197, 94)
    private val hrcRed = Color.rgb(255, 104, 120)
    private val hbGreen = Color.rgb(75, 214, 155)
    private val hvBlue = Color.rgb(60, 191, 255)
    private data class Measurement(val first: String, val second: String, val result: String)
    private val vickersHistory = mutableListOf<Measurement>()
    private val brinellHistory = mutableListOf<Measurement>()
    private val muted = Color.rgb(164, 178, 194)
    private lateinit var root: LinearLayout
    private lateinit var resultDock: TextView
    private var keyboardVisible = false
    private lateinit var contentScroll: ScrollView
    private lateinit var contentFrame: LinearLayout
    private var activeInput: EditText? = null
    private var latestResult = ""
    private var lastDockVisible = false
    private fun refreshDock() {
        if (!::resultDock.isInitialized) return
        resultDock.text = latestResult
        resultDock.visibility = if (keyboardVisible && (currentTab == 0 || currentTab == 1 || latestResult.isNotBlank())) View.VISIBLE else View.GONE
        if (keyboardVisible) resultDock.bringToFront()
        if (keyboardVisible && latestResult.isBlank()) resultDock.text = "Результат появится после ввода"
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=dp(16).toFloat(); if(stroke!=null) setStroke(dp(1),stroke) }
    private fun showResult(message: String) { latestResult = message; refreshDock() }
    private fun actionButtons(p: LinearLayout, fields: List<EditText>, result: TextView? = null, onSubmit: (() -> Unit)? = null) {
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        if(result!=null) {
            result.gravity=Gravity.CENTER_VERTICAL
            result.textSize=19f
            bar.addView(result,LinearLayout.LayoutParams(0,dp(42),1.15f))
        }
        val submit=Button(this).apply { text="Ввод";isAllCaps=false;textSize=12f;setTextColor(Color.BLACK);background=shape(accent);setOnClickListener { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken,0);currentFocus?.clearFocus(); onSubmit?.invoke() } }
        val clear=Button(this).apply { text="Очистка";isAllCaps=false;textSize=12f;setTextColor(Color.WHITE);background=shape(Color.rgb(68,80,95));setOnClickListener { fields.forEach { it.text.clear() };showResult("") } }
        val weight=if(result==null) 1f else 0.95f
        val l=LinearLayout.LayoutParams(0,dp(42),weight);l.setMargins(dp(2),dp(5),dp(2),dp(4));bar.addView(submit,l)
        val r=LinearLayout.LayoutParams(0,dp(42),weight);r.setMargins(dp(2),dp(5),dp(2),dp(4));bar.addView(clear,r)
        p.addView(bar)
    }
    private val prefs by lazy { getSharedPreferences("hardnesslab_modes_v21", Context.MODE_PRIVATE) }
    private fun remember(key: String, widget: Spinner) {
        widget.setSelection(prefs.getInt(key, 0).coerceIn(0, widget.adapter.count - 1))
        widget.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(key, position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
    private fun labeled(p: LinearLayout, label: String, input: EditText) {
        p.addView(text(label, 13f, Color.LTGRAY)); p.addView(input)
    }
    private fun pairedFields(p: LinearLayout, a: EditText, b: EditText, kind: String) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(a,b).forEachIndexed { index, edit ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(text("$kind ${index + 1}", 12f, Color.LTGRAY))
            column.addView(edit, LinearLayout.LayoutParams(-1, dp(40)))
            line.addView(column, LinearLayout.LayoutParams(0,-2,1f).apply {
                setMargins(if(index==0) 0 else dp(5),0,if(index==0) dp(5) else 0,0)
            })
        }
        p.addView(line)
    }
    private fun compactSelectors(p: LinearLayout, first: Spinner, second: Spinner) {
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        listOf(first,second).forEachIndexed { i, widget ->
            val column = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            column.addView(text(if(i==0) "Режим" else "Единицы",12f,Color.LTGRAY))
            column.addView(widget,LinearLayout.LayoutParams(-1,dp(40)))
            row.addView(column,LinearLayout.LayoutParams(0,-2,if(i==0) 1.7f else 1f).apply {
                setMargins(if(i==0) 0 else dp(4),0,if(i==0) dp(4) else 0,0)
            })
        }
        p.addView(row)
    }
    private fun clearButton(p: LinearLayout, fields: List<EditText>) = actionButtons(p,fields)
    private var currentTab = 0
    private lateinit var conversionTables: ConversionTables
    private lateinit var thicknessTables: ThicknessTables
    private fun text(s: String, size: Float = 17f, color: Int = Color.WHITE): TextView = TextView(this).apply {
        text=s; textSize=size; setTextColor(color); setPadding(0,dp(2),0,dp(2)); gravity=android.view.Gravity.CENTER_VERTICAL
    }
    private fun field(hintText: String, initial: String): EditText = EditText(this).apply {
        hint=hintText; setText(initial); setTextColor(Color.WHITE); setHintTextColor(Color.rgb(150, 164, 179))
        textSize=16f; minHeight=dp(40); background=shape(Color.rgb(30, 46, 64), Color.rgb(54, 77, 96)); ellipsize=android.text.TextUtils.TruncateAt.END
        inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _,_,_ -> (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0);clearFocus();true }
        setSingleLine(true)
        setPadding(dp(12),dp(5),dp(12),dp(5))
        setOnFocusChangeListener { _, focused -> if(focused) {
            activeInput=this
            postDelayed({
                val r=Rect(0,0,width,height+dp(6))
                requestRectangleOnScreen(r,true)
            },180)
        } }
    }
    private fun spinner(options: List<String>): Spinner = Spinner(this).apply {
        val rowHeight = dp(40)
        val normal = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return TextView(this@MainActivity).apply {
                    text = getItem(position); textSize = 15f; setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, dp(34), 0)
                    minHeight = rowHeight
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return TextView(this@MainActivity).apply {
                    text = getItem(position); textSize = 15f; setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    minHeight = dp(40)
                    background = shape(if(position % 2 == 0) Color.rgb(32,48,66) else panel)
                }
            }
        }
        adapter = normal
        minimumHeight = rowHeight
        background = shape(Color.rgb(30,46,64),Color.rgb(54,77,96))
        dropDownWidth = resources.displayMetrics.widthPixels - dp(48)
        dropDownVerticalOffset = dp(4)
    }
    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(9),dp(12),dp(10)); background=shape(panel,Color.rgb(46,93,114))
        val lp=LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(6),0,dp(6)); root.addView(this,lp)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thicknessTables=ThicknessTables(this)
        conversionTables=assets.open("hardness_conversion.tsv").bufferedReader().use { ConversionTables(it.readLines()) }
        showScreen(0)
    }
    private fun showScreen(tab: Int) {
        currentTab=tab
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        latestResult=""; keyboardVisible=false
        window.statusBarColor=bg
        window.navigationBarColor=bg
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(12)); setBackgroundColor(bg) }
        val scroll=ScrollView(this).apply { isFillViewport=false; addView(root); clipToPadding=false }
        contentScroll=scroll
        val frame=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg) }
        frame.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        contentFrame=frame
        resultDock=TextView(this).apply { textSize=19f;setTextColor(accent);setPadding(dp(14),dp(6),dp(14),dp(6));background=shape(Color.rgb(17,48,63),accent);visibility=View.GONE }
        resultDock.maxLines=1
        resultDock.ellipsize=android.text.TextUtils.TruncateAt.END
        frame.addView(resultDock,LinearLayout.LayoutParams(-1,-2));setContentView(frame)
        // The result bar belongs to the resized window, not to the scrolling form.
        // Detect the visible viewport even on Android builds that do not dispatch IME insets.
        frame.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            frame.getWindowVisibleDisplayFrame(visible)
            val screenHeight = frame.rootView.height
            val obscured = (screenHeight - visible.bottom).coerceAtLeast(0)
            val opened = obscured > dp(120)
            if (keyboardVisible != opened) {
                keyboardVisible = opened
                refreshDock()
            }
            if (opened) activeInput?.let { input ->
                if (input.hasFocus()) input.post {
                    val rect = Rect(0,0,input.width,input.height+dp(14))
                    input.requestRectangleOnScreen(rect,false)
                }
            }
        }

        root.addView(text("HARDNESS LAB",20f,accent).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL; minHeight=dp(36) })
        // Compact header: leave more vertical space for the active input.
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL };root.addView(tabs)
        listOf("Виккерс","Бринелль","Толщина","Перевод").forEachIndexed { i,label ->
            tabs.addView(Button(this).apply { text=label; isAllCaps=false; textSize=12f;setTextColor(if(i==tab) accent else Color.WHITE);background=shape(if(i==tab) Color.rgb(22,65,83) else Color.rgb(43,57,71),if(i==tab) accent else null); setOnClickListener { showScreen(i) } },LinearLayout.LayoutParams(0,dp(39),1f).apply {setMargins(dp(2),dp(3),dp(2),dp(3))})
        }
        when(tab) { 0 -> vickersScreen(); 1 -> brinellScreen(); 2 -> thicknessScreen(); else -> conversionScreen() }
        root.addView(text("Офлайн • HardnessLab 3.4 RC2 • Справочные данные",11f,Color.GRAY))
    }
    private fun conversionScreen() {
        val p=panel()
        p.addView(text("Перевод твёрдости",19f))
        p.addView(text("Справочные значения · без экстраполяции. Перевод не заменяет испытание.",12f,Color.LTGRAY))
        if(conversionTables.series.isEmpty()) {
            p.addView(text("Справочник недоступен",14f,hrcRed)); return
        }
        val mode=spinner(listOf("Все документы","Один документ"))
        p.addView(text("Режим",13f,Color.LTGRAY));p.addView(mode)
        val docs=conversionTables.series.map { it.document }.distinct()
        val doc=spinner(docs)
        val docLabel=text("Документ",13f,Color.LTGRAY)
        p.addView(docLabel);p.addView(doc)
        val material=spinner(listOf("Все материалы")+conversionTables.series.map { it.material }.distinct())
        p.addView(text("Материал",13f,Color.LTGRAY));p.addView(material)
        val scaleLabels=listOf("HRC","HB/HBW","HV")
        val scale=spinner(scaleLabels)
        p.addView(text("Исходная шкала",13f,Color.LTGRAY));p.addView(scale)
        val input=field("Например: 45","")
        p.addView(text("Твёрдость",13f,muted)); p.addView(input)
        clearButton(p,listOf(input))
        val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        p.addView(results)
        val update = {
            doc.visibility=if(mode.selectedItemPosition==1) View.VISIBLE else View.GONE
            docLabel.visibility=doc.visibility
            results.removeAllViews()
            val x=input.text.toString().replace(',','.').toDoubleOrNull()
            if(x!=null && x.isFinite() && x>0) {
                val from=scale.selectedItemPosition
                val targetScales=(0..2).filter { it!=from }
                val candidates=conversionTables.series.filter { s ->
                    (mode.selectedItemPosition==0 || s.document==docs[doc.selectedItemPosition.coerceIn(docs.indices)]) &&
                    (material.selectedItemPosition==0 || s.material==material.selectedItem.toString())
                }
                var count=0
                val supported = candidates.filter { it.supports(from) }
                supported.forEach { s ->
                    val answers=targetScales.map { to -> to to conversionTables.convert(s,from,to,x) }
                    run {
                        if(answers.any { it.second.value!=null }) count++
                        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(7),dp(10),dp(7));background=shape(Color.rgb(31,48,66)) }
                        card.addView(text("${s.document} · ${s.table}",15f,accent).apply { setTypeface(null,Typeface.BOLD) })
                        card.addView(text(s.material,12f,Color.LTGRAY))
                        card.addView(text(s.note,11f,if(s.checked) muted else hrcRed))
                        val columns=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
                        answers.forEach { (to,a) ->
                            val label=s.label(to)
                            val color=listOf(hrcRed,hbGreen,hvBlue)[to]
                            val cell=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(7),dp(5),dp(7),dp(5));gravity=Gravity.CENTER; background=shape(Color.rgb(23,39,55))}
                            cell.addView(text(label,12f,color).apply {setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER})
                            cell.addView(text(if(a.value==null) "—" else a.value.conversionLabel(),19f,if(a.value==null) muted else color).apply {gravity=Gravity.CENTER})
                            cell.addView(text(a.status,11f,muted).apply { gravity=Gravity.CENTER })
                            if(a.nearest!=null && a.nearestInput!=null) {
                                cell.addView(text("Ближайшее: ${a.nearestInput.conversionLabel()} ${s.label(from)} → ${a.nearest.conversionLabel()} $label",10f,muted).apply {gravity=Gravity.CENTER})
                            }
                            columns.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply {setMargins(dp(3),dp(3),dp(3),0)})
                        }
                        card.addView(columns)
                        results.addView(card,LinearLayout.LayoutParams(-1,-2).apply {setMargins(0,dp(4),0,dp(4))})
                    }
                }
                val unavailable = candidates.filterNot { it.checked }
                if(unavailable.isNotEmpty()) results.addView(text("Справочник не прошёл проверку: ${unavailable.joinToString { "${it.document} ${it.table}" }}",12f,hrcRed))
                val unsupported = candidates.filter { it.checked && !it.supports(from) }
                if(unsupported.isNotEmpty()) results.addView(text("Для ${scaleLabels[from]} нет пары в: ${unsupported.map { "${it.material} (${it.table})" }.distinct().joinToString()}",12f,muted))
                if(count==0) results.addView(text("Нет допустимых переводов для выбранных условий",14f,Color.LTGRAY))
                showResult("Перевод · $count наборов таблиц")
            } else {
                if(input.text.isNotBlank()) results.addView(text("Введите положительное число",14f,hrcRed))
                showResult("")
            }
        }
        watch(listOf(input),listOf(mode,doc,material,scale),update)
    }

    private fun watch(inputs: List<EditText>, spinners: List<Spinner>, update: () -> Unit) {
        inputs.forEach { it.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { update() }
            override fun afterTextChanged(s:Editable?) {}
        }) }
        spinners.forEachIndexed { index, it ->
            val key="tab_${currentTab}_spinner_$index"
            it.setSelection(prefs.getInt(key,0).coerceIn(0,it.adapter.count-1))
            it.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,position:Int,id:Long) { prefs.edit().putInt(key,position).apply(); update() }
            override fun onNothingSelected(p:android.widget.AdapterView<*>?) {}
        } };update()
    }
    private fun Double.pretty(): String = String.format(Locale("ru"),"%.2f",this)
    // Display up to one decimal for conversions; the figures are approximate, not precise measurements.
    private fun Double.conversionLabel(): String = if(kotlin.math.abs(this-kotlin.math.round(this))<1e-8) this.toLong().toString() else String.format(Locale("ru"),"%.1f",this)
    private fun EditText.num(): Double = text.toString().replace(',','.').toDouble()
    private val vLoads = listOf(0.2,0.3,0.5,1.0,2.0,3.0,5.0,10.0,20.0,30.0,50.0)
    private val bLoads = listOf(62.5,100.0,125.0,187.5,250.0,500.0,750.0,1000.0,1500.0,3000.0)
    private fun Double.shortLabel(): String = if (this == toLong().toDouble()) toLong().toString() else toString()
    private fun loadMenu(values: List<Double>, units: Spinner, load: Spinner, key: String, update: () -> Unit) {
        val kgf = units.selectedItemPosition == 0
        val old = prefs.getInt(key, 0).coerceIn(0,values.lastIndex)
        val labels = values.map {
            if (kgf) "${it.shortLabel()} кгс" else "${(it*9.80665).pretty()} Н"
        }
        val current = (0 until load.adapter.count).map { load.adapter.getItem(it).toString() }
        if (current != labels) {
            load.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,labels)
            load.setSelection(old)
        }
        if (load.onItemSelectedListener != null && current == labels) { update(); return }
        load.onItemSelectedListener = object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,pos:Int,id:Long) { prefs.edit().putInt(key,pos).apply();update() }
            override fun onNothingSelected(p:android.widget.AdapterView<*>?) {}
        }
        update()
    }
    private fun measurementHistory(p: LinearLayout, entries: MutableList<Measurement>, scale: String): LinearLayout {
        val section=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        p.addView(section)
        renderMeasurementHistory(section,entries,scale)
        return section
    }
    private fun renderMeasurementHistory(section: LinearLayout, entries: List<Measurement>, scale: String) {
        section.removeAllViews()
        section.addView(text("История измерений · ${entries.size}",15f,Color.WHITE).apply {setTypeface(null,Typeface.BOLD)})
        val values=entries.mapNotNull { it.result.replace(',','.').toDoubleOrNull()?.takeIf { n -> n.isFinite() } }
        if(values.isNotEmpty()) section.addView(text("Среднее: $scale ${values.average().pretty()}",16f,if(scale=="HB") hbGreen else hvBlue).apply {setTypeface(null,Typeface.BOLD)})
        if(entries.isEmpty()) section.addView(text("Нет сохранённых измерений",12f,muted))
        entries.asReversed().forEachIndexed { idx, entry ->
            val line=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(7),dp(10),dp(7));background=shape(Color.rgb(31,48,66))}
            val details=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
            details.addView(text("№ ${entries.size-idx}   d1 / d2",11f,muted))
            details.addView(text("${entry.first} / ${entry.second}",13f,Color.WHITE))
            line.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            line.addView(text("$scale ${entry.result}",20f,if(scale=="HB") hbGreen else hvBlue).apply {setTypeface(null,Typeface.BOLD)})
            section.addView(line,LinearLayout.LayoutParams(-1,-2).apply {setMargins(0,dp(3),0,dp(3))})
        }
        val erase=Button(this).apply {
            text="Очистить данные";isAllCaps=false;textSize=12f;setTextColor(muted)
            background=shape(Color.rgb(35,48,65))
            isEnabled=entries.isNotEmpty()
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Очистить историю?")
                    .setMessage("Все сохранённые измерения $scale будут удалены.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Очистить") { _,_ ->
                        if(scale=="HV") vickersHistory.clear() else brinellHistory.clear()
                        renderMeasurementHistory(section,if(scale=="HV") vickersHistory else brinellHistory,scale)
                    }.show()
            }
        }
        section.addView(erase,LinearLayout.LayoutParams(-1,dp(38)).apply {setMargins(0,dp(4),0,0)})
    }
    private fun appendMeasurement(section: LinearLayout, entries: MutableList<Measurement>, first: EditText, second: EditText, result: TextView, scale: String) {
        val value=result.text.toString()
        if(!value.startsWith("$scale  ")) return
        val number=value.substringAfter("$scale  ").replace(',','.').toDoubleOrNull()
        if(number==null || !number.isFinite() || number<=0.0) return
        entries.add(Measurement(first.text.toString(),second.text.toString(),value.substringAfter("$scale  ")))
        renderMeasurementHistory(section,entries,scale)
    }
    private fun vickersScreen() {
        val p=panel();p.addView(text("Виккерс · HV",18f))
        val mode=spinner(listOf("Прямой ввод диагоналей, мм","X10","X20","Микротвёрдость (0,3 мкм/дел.)"))
        val units=spinner(listOf("Нагрузка, кгс","Нагрузка, Н"))
        if(!prefs.contains("tab_0_spinner_0")) prefs.edit().putInt("tab_0_spinner_0",2).apply()
        compactSelectors(p,mode,units)
        p.addView(text("Нагрузка",13f,Color.LTGRAY));val load=spinner(listOf("—"));p.addView(load)
        val a=field("Диагональ 1", "");val b=field("Диагональ 2", "")
        pairedFields(p,a,b,"Диагональ")
        val out=text("",19f,hvBlue)
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };p.addView(controls)
        val history=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        actionButtons(controls,listOf(a,b),out) { appendMeasurement(history,vickersHistory,a,b,out,"HV") }
        p.addView(history)
        renderMeasurementHistory(history,vickersHistory,"HV")
        val update = {
            if(a.text.isBlank()||b.text.isBlank()) out.text="" else out.text=try {
                val kgf=vLoads[load.selectedItemPosition.coerceIn(0,vLoads.lastIndex)]
                val newtons=units.selectedItemPosition==1
                val result=Hardness.vickers(a.num(),b.num(),if(newtons) kgf*9.80665 else kgf,mode.selectedItemPosition,newtons)
                require(result.isFinite() && result>0);"HV  ${result.pretty()}"
            } catch(_:Exception) {"Проверьте введённые значения"}
        }
        out.addTextChangedListener(object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){showResult(s?.toString().orEmpty())};override fun afterTextChanged(s:Editable?){} })
        watch(listOf(a,b),listOf(mode,units)) { loadMenu(vLoads,units,load,"v_load",update) }
    }
    private fun brinellScreen() {
        val p=panel();p.addView(text("Бринелль · HB",18f))
        val microscope=spinner(listOf("Диаметр отпечатка, мм","Отсчёт микроскопа (×0,01 мм)"))
        val units=spinner(listOf("Нагрузка, кгс","Нагрузка, Н"))
        compactSelectors(p,microscope,units)
        p.addView(text("Диаметр шарика, мм",14f,Color.LTGRAY))
        val ball=spinner(listOf("1 мм","2,5 мм","5 мм","10 мм"));p.addView(ball)
        p.addView(text("Нагрузка",14f,Color.LTGRAY));val load=spinner(listOf("—"));p.addView(load)
        val a=field("Отпечаток 1", "");val b=field("Отпечаток 2", "")
        pairedFields(p,a,b,"Отпечаток")
        val out=text("",19f,hbGreen)
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };p.addView(controls)
        val history=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        actionButtons(controls,listOf(a,b),out) { appendMeasurement(history,brinellHistory,a,b,out,"HB") }
        p.addView(history)
        renderMeasurementHistory(history,brinellHistory,"HB")
        val update = {
            if(a.text.isBlank()||b.text.isBlank()) out.text="" else out.text=try {
                val diameter=listOf(1.0,2.5,5.0,10.0)[ball.selectedItemPosition]
                val kgf=bLoads[load.selectedItemPosition.coerceIn(0,bLoads.lastIndex)]
                val newtons=units.selectedItemPosition==1
                val result=Hardness.brinell(diameter,a.num(),b.num(),if(newtons) kgf*9.80665 else kgf,newtons,microscope.selectedItemPosition==1)
                require(result.isFinite() && result>0);"HB  ${result.pretty()}"
            } catch(_:Exception) {"Проверьте: 0 < отпечаток < шарик"}
        }
        out.addTextChangedListener(object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){showResult(s?.toString().orEmpty())};override fun afterTextChanged(s:Editable?){} })
        watch(listOf(a,b),listOf(microscope,units,ball)) { loadMenu(bLoads,units,load,"b_load",update) }
    }
    // ГОСТ 9012-59, таблицы 2 и 3: K = F(кгс) / D²(мм²).
    private fun brinellK(material: Int, hb: Double): Double? = when(material) {
        0 -> if(hb < 140.0) 10.0 else 30.0 // сталь, чугун, высокопрочные сплавы
        1 -> if(hb >= 50.0) 15.0 else null // титан
        2 -> if(hb < 35.0) 5.0 else 10.0 // медь, лёгкие металлы
        3 -> if(hb in 8.0..50.0) 2.5 else null // подшипниковые сплавы
        4 -> if(hb <= 20.0) 1.0 else null // мягкие металлы
        else -> null
    }
    private fun brinellStandardLoads(d: Double, k: Double): List<Double> = when(d) {
        2.5 -> when(k) { 30.0->listOf(187.5);10.0->listOf(62.5);5.0->listOf(31.2);2.5->listOf(15.6);1.0->listOf(6.2);else->emptyList() }
        5.0 -> when(k) { 30.0->listOf(750.0);10.0->listOf(250.0);5.0->listOf(125.0);2.5->listOf(62.5);1.0->listOf(25.0);else->emptyList() }
        10.0 -> when(k) {30.0->listOf(3000.0);15.0->listOf(1500.0);10.0->listOf(1000.0);5.0->listOf(500.0);2.5->listOf(250.0);1.0->listOf(100.0);else->emptyList() }
        else -> emptyList()
    }
    private fun thicknessScreen() {
        val p=panel();p.addView(text("Минимальная толщина образца",19f))
        p.addView(text("Все нагрузки из справочных таблиц Excel v2.0",13f,Color.LTGRAY))
        val method=spinner(listOf("Роквелл (HRC)","Виккерс · ГОСТ Р ИСО 6507-4-2007","Виккерс · ГОСТ 2999-59","Виккерс · ГОСТ 9450-76","Бринелль · ГОСТ 9012-59"))
        p.addView(text("Метод и стандарт",14f,Color.LTGRAY));p.addView(method)
        val materialLabel=text("Материал (ГОСТ 9012-59, табл. 2)",14f,Color.LTGRAY);p.addView(materialLabel)
        val material=spinner(listOf("Сталь, чугун, высокопрочные сплавы","Титан и сплавы","Медь и лёгкие металлы, сплавы","Подшипниковые сплавы","Свинец, олово, мягкие металлы"));p.addView(material)
        val vMaterialLabel=text("Материал (таблицы Виккерса)",14f,Color.LTGRAY);p.addView(vMaterialLabel)
        val vMaterial=spinner(listOf("Сталь","Цветные металлы"));p.addView(vMaterial)
        val hardness=field("Например: 25", "")
        val hardnessLabel=text("Прогнозируемая твёрдость HRC",14f,Color.LTGRAY)
        p.addView(hardnessLabel);p.addView(hardness);clearButton(p,listOf(hardness))
        val out=text("",18f,accent)
        val resultsContainer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        p.addView(resultsContainer)
        p.addView(out)
        fun renderRows(rows: List<String>) {
            resultsContainer.removeAllViews()
            if(rows.isEmpty()) return
            val heading=text("Результаты · ${rows.size}",17f,Color.WHITE)
            heading.setTypeface(null,Typeface.BOLD)
            resultsContainer.addView(heading)
            val headers=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
            listOf("Шкала / шарик", "Нагрузка", "Мин. толщина", "").forEachIndexed { i,label ->
                headers.addView(text(label,10f,Color.LTGRAY).apply { gravity=if(i==3) android.view.Gravity.CENTER else android.view.Gravity.CENTER_VERTICAL; maxLines=2 },LinearLayout.LayoutParams(0,-2,listOf(1.25f,1.35f,1.25f,0.35f)[i]))
            }
            resultsContainer.addView(headers)
            rows.forEachIndexed { index, entry ->
                val d=Regex("D=([^·]+)").find(entry)?.groupValues?.get(1)?.trim()
                val f=Regex("F=([^·]+)").find(entry)?.groupValues?.get(1)?.trim()
                val thick=entry.substringAfterLast(": ","—")
                val scale=if(d!=null) d else entry.substringBefore(":")
                val loadValue=if(f!=null) f.substringBefore(" (") else "—"
                val cells=listOf(scale,loadValue,thick,"✓")
                val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL; minimumHeight=dp(42); setPadding(dp(3),dp(5),dp(3),dp(5)); if(index%2==0) background=shape(Color.rgb(29,44,61)) }
                cells.forEachIndexed { i,value ->
                    val cell=text(value,if(i==3) 20f else 12f,if(i==3) successGreen else if(i==2) accent else Color.WHITE).apply { gravity=if(i==3) android.view.Gravity.CENTER else android.view.Gravity.CENTER_VERTICAL; maxLines=2 }
                    row.addView(cell,LinearLayout.LayoutParams(0,-2,listOf(1.25f,1.35f,1.25f,0.35f)[i]))
                }
                resultsContainer.addView(row)
            }
        }
        val note=text("",13f,Color.LTGRAY);p.addView(note)
        watch(listOf(hardness),listOf(method,material,vMaterial)) {
            val m=method.selectedItemPosition
            material.visibility=if(m==4) View.VISIBLE else View.GONE;materialLabel.visibility=material.visibility
            vMaterial.visibility=if(m==2||m==3) View.VISIBLE else View.GONE;vMaterialLabel.visibility=vMaterial.visibility
            hardnessLabel.text="Прогнозируемая твёрдость "+when(m){0->"HRC";4->"HB";else->"HV"}
            if(hardness.text.isBlank()) {out.text="";note.text="";renderRows(emptyList());showResult("");return@watch}
            val h=hardness.text.toString().replace(',','.').toDoubleOrNull()
            if(h==null||!h.isFinite()||h<=0){renderRows(emptyList());out.text="Введите положительную твёрдость";note.text="";return@watch}
            val rows=mutableListOf<String>()
            val mat=if(vMaterial.selectedItemPosition==0) "steel" else "nonferrous"
            when(m) {
                0 -> thicknessTables.lookup("rockwell",h,true)?.let { rows.add("HRC: ${it.pretty()} мм") }
                1 -> listOf("02" to "HV0,2","03" to "HV0,3","05" to "HV0,5","1" to "HV1","5" to "HV5","10" to "HV10","20" to "HV20","30" to "HV30","50" to "HV50").forEach { (key,label) ->
                    thicknessTables.lookup("iso"+key,h)?.let { rows.add("$label: ${it.pretty()} мм") }
                }
                2 -> listOf("1","5","10","20","30").forEach { load ->
                    thicknessTables.lookup("2999_"+mat+"_"+load,h)?.let { rows.add("HV$load: ${it.pretty()} мм") }
                }
                3 -> listOf("02" to "HV0,2","05" to "HV0,5").forEach { (key,label) ->
                    thicknessTables.lookup("9450_"+mat+"_"+key,h)?.let { rows.add("$label: ${it.pretty()} мм") }
                }
                4 -> {
                    if(h>650.0) {renderRows(emptyList());out.text="Вне области ГОСТ 9012-59: HB > 650";note.text="";return@watch}
                    val k=brinellK(material.selectedItemPosition,h)
                    if(k==null) {renderRows(emptyList());out.text="Для материала и твёрдости нет допустимого K по таблице 2";note.text="";return@watch}
                    for(d in listOf(2.5,5.0,10.0)) for(load in brinellStandardLoads(d,k)) {
                        val key="hb_"+d.shortLabel()+"_"+load.shortLabel()
                        val value=thicknessTables.lookup(key,h)
                        if(value!=null && value.isFinite() && value>0) {
                            // Предварительная проверка ожидаемого d/D по формуле Бринелля.
                            val q=2.0*load/(Math.PI*d*h)
                            val discriminant=d*d-(d-q)*(d-q)
                            val ratio=if(discriminant>=0) sqrt(discriminant)/d else Double.NaN
                            if(ratio.isFinite() && ratio>=0.24 && ratio<=0.6)
                                rows.add("D=${d.shortLabel()} мм · F=${load.shortLabel()} кгс (${(load*9.80665).pretty()} Н) · K=${k.shortLabel()}: ${value.pretty()} мм")
                        }
                    }
                    note.text="ГОСТ 9012-59: таблицы 2–3, d/D от 0,24 до 0,60; толщина — из Excel. Проверка d/D предварительная, по прогнозу HB."
                }
            }
            renderRows(rows)
            out.text=if(rows.isEmpty()) "Нет допустимых табличных сочетаний для введённой твёрдости" else ""
            showResult(if(rows.isEmpty()) "Нет допустимых режимов" else "Найдено режимов: ${rows.size}")
            if(m!=4) note.text="Значения из Excel v2.0. Нагрузки без табличных данных не показаны."
        }
    }
}
