package com.hardnesslab.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.WindowInsets
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
        val mean = (d1 + d2) / 2
        val diagonalMm = when(mode) { 0 -> mean; 1 -> mean / 1000; 2 -> mean / 2000; else -> mean * 0.3 / 1000 }
        require(diagonalMm > 0 && load > 0)
        return (if(newtons) 0.1891 else 1.854) * load / (diagonalMm * diagonalMm)
    }
    fun brinell(ball: Double, d1: Double, d2: Double, load: Double, newtons: Boolean, microscope100: Boolean): Double {
        val d = (d1 + d2) / 2 * (if(microscope100) 0.01 else 1.0)
        require(ball > 0 && d > 0 && d < ball && load > 0)
        return (if(newtons) 0.102 else 1.0) * 2 * load / (3.14159 * ball * (ball - sqrt(ball * ball - d * d)))
    }
    fun thickness(hv: Double, loadKgf: Double, factor: Double): Double {
        require(hv > 0 && loadKgf > 0)
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
    private lateinit var root: LinearLayout
    private lateinit var resultDock: TextView
    private var keyboardVisible = false
    private var activeInput: EditText? = null
    private var latestResult = ""
    private fun refreshDock() {
        if (!::resultDock.isInitialized) return
        resultDock.text = latestResult
        resultDock.visibility = if (keyboardVisible && latestResult.isNotBlank()) View.VISIBLE else View.GONE
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=dp(16).toFloat(); if(stroke!=null) setStroke(dp(1),stroke) }
    private fun showResult(message: String) { latestResult = message; refreshDock() }
    private fun actionButtons(p: LinearLayout, fields: List<EditText>) {
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val submit=Button(this).apply { text="Ввод";isAllCaps=false;setTextColor(Color.BLACK);background=shape(accent);setOnClickListener { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken,0);currentFocus?.clearFocus() } }
        val clear=Button(this).apply { text="Очистить";isAllCaps=false;setTextColor(Color.WHITE);background=shape(Color.rgb(68,80,95));setOnClickListener { fields.forEach { it.text.clear() };showResult("") } }
        val l=LinearLayout.LayoutParams(0,dp(48),1f);l.setMargins(dp(3),dp(10),dp(3),dp(8));bar.addView(submit,l)
        val r=LinearLayout.LayoutParams(0,dp(48),1f);r.setMargins(dp(3),dp(10),dp(3),dp(8));bar.addView(clear,r);p.addView(bar)
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
    private fun clearButton(p: LinearLayout, fields: List<EditText>) = actionButtons(p,fields)
    private var currentTab = 0
    private lateinit var thicknessTables: ThicknessTables
    private fun text(s: String, size: Float = 17f, color: Int = Color.WHITE): TextView = TextView(this).apply {
        text=s; textSize=size; setTextColor(color); setPadding(0,dp(7),0,dp(7))
    }
    private fun field(hintText: String, initial: String): EditText = EditText(this).apply {
        hint=hintText; setText(initial); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _,_,_ -> (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0);clearFocus();true }
        setSingleLine(true)
        setPadding(dp(10),dp(8),dp(10),dp(8))
        setOnFocusChangeListener { _, focused -> if(focused) activeInput=this }
    }
    private fun spinner(options: List<String>): Spinner = Spinner(this).apply {
        adapter=ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
        minimumHeight=dp(46)
    }
    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(14)); background=shape(panel,Color.rgb(46,93,114))
        val lp=LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(10),0,dp(8)); root.addView(this,lp)
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); thicknessTables=ThicknessTables(this); showScreen(0) }
    private fun showScreen(tab: Int) {
        currentTab=tab
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        latestResult=""; keyboardVisible=false
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(bg) }
        val scroll=ScrollView(this).apply { fillViewport=false; addView(root) }
        val frame=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg) }
        frame.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        resultDock=TextView(this).apply { textSize=17f;setTextColor(accent);setPadding(dp(14),dp(8),dp(14),dp(8));background=shape(Color.rgb(17,48,63),accent);visibility=View.GONE }
        resultDock.maxLines=1
        resultDock.ellipsize=android.text.TextUtils.TruncateAt.END
        frame.addView(resultDock,LinearLayout.LayoutParams(-1,-2));setContentView(frame)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frame.setOnApplyWindowInsetsListener { _, insets ->
                val opened=insets.isVisible(WindowInsets.Type.ime())
                if (keyboardVisible!=opened) { keyboardVisible=opened; refreshDock() }
                insets
            }
        } else {
            frame.viewTreeObserver.addOnGlobalLayoutListener {
                val rect=android.graphics.Rect()
                frame.getWindowVisibleDisplayFrame(rect)
                val opened=(frame.rootView.height-rect.bottom)>dp(170)
                if (keyboardVisible!=opened) { keyboardVisible=opened; refreshDock() }
            }
        }
        root.addView(text("HARDNESS LAB",25f,accent).apply { setTypeface(null,Typeface.BOLD) })
        root.addView(text("Расчёт твёрдости материалов",13f,Color.LTGRAY))
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL };root.addView(tabs)
        listOf("Виккерс","Бринелль","Толщина").forEachIndexed { i,label ->
            tabs.addView(Button(this).apply { text=label; isAllCaps=false; textSize=12f;setTextColor(if(i==tab) accent else Color.WHITE);background=shape(if(i==tab) Color.rgb(22,65,83) else Color.rgb(43,57,71),if(i==tab) accent else null); setOnClickListener { showScreen(i) } },LinearLayout.LayoutParams(0,dp(49),1f).apply {setMargins(dp(2),dp(3),dp(2),dp(3))})
        }
        when(tab) { 0 -> vickersScreen(); 1 -> brinellScreen(); else -> thicknessScreen() }
        root.addView(text("Офлайн • Справочные данные Excel v2",11f,Color.GRAY))
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
    private fun EditText.num(): Double = text.toString().replace(',','.').toDouble()
    private val vLoads = listOf(0.2,0.3,0.5,1.0,2.0,3.0,5.0,10.0,20.0,30.0,50.0)
    private val bLoads = listOf(62.5,100.0,125.0,187.5,250.0,500.0,750.0,1000.0,1500.0,3000.0)
    private fun Double.shortLabel(): String = if (this == toLong().toDouble()) toLong().toString() else toString()
    private fun loadMenu(values: List<Double>, units: Spinner, load: Spinner, key: String, update: () -> Unit) {
        val kgf = units.selectedItemPosition == 1
        val old = prefs.getInt(key, 0).coerceIn(0,values.lastIndex)
        load.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,values.map {
            if (kgf) "${it.shortLabel()} кгс" else "${(it*9.80665).pretty()} Н"
        })
        load.setSelection(old)
        load.onItemSelectedListener = object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,pos:Int,id:Long) { prefs.edit().putInt(key,pos).apply();update() }
            override fun onNothingSelected(p:android.widget.AdapterView<*>?) {}
        }
        update()
    }
    private fun vickersScreen() {
        val p=panel();p.addView(text("Твёрдость по Виккерсу",22f))
        val mode=spinner(listOf("Прямой ввод диагоналей, мм","Микроскоп X10","Микроскоп X20","Микротвёрдость (0,3 мкм/дел.)"));p.addView(mode)
        val units=spinner(listOf("Нагрузка, Н","Нагрузка, кгс"));p.addView(units)
        p.addView(text("Выберите нагрузку",14f,Color.LTGRAY));val load=spinner(listOf("—"));p.addView(load)
        val a=field("Диагональ 1", "");val b=field("Диагональ 2", "")
        labeled(p,"Диагональ 1 (в единицах режима)",a);labeled(p,"Диагональ 2 (в единицах режима)",b)
        clearButton(p,listOf(a,b));val out=text("",28f,accent);p.addView(out)
        val update = {
            if(a.text.isBlank()||b.text.isBlank()) out.text="" else out.text=try {
                val kgf=vLoads[load.selectedItemPosition.coerceIn(0,vLoads.lastIndex)]
                val newtons=units.selectedItemPosition==0
                val result=Hardness.vickers(a.num(),b.num(),if(newtons) kgf*9.80665 else kgf,mode.selectedItemPosition,newtons)
                require(result.isFinite() && result>0);"HV  ${result.pretty()}"
            } catch(_:Exception) {"Проверьте введённые значения"}
        }
        out.addTextChangedListener(object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){showResult(s?.toString().orEmpty())};override fun afterTextChanged(s:Editable?){} })
        watch(listOf(a,b),listOf(mode,units)) { loadMenu(vLoads,units,load,"v_load",update) }
    }
    private fun brinellScreen() {
        val p=panel();p.addView(text("Твёрдость по Бринеллю",22f))
        val microscope=spinner(listOf("Диаметр отпечатка, мм","Отсчёт микроскопа (×0,01 мм)"));p.addView(microscope)
        val units=spinner(listOf("Нагрузка, Н","Нагрузка, кгс"));p.addView(units)
        p.addView(text("Диаметр шарика, мм",14f,Color.LTGRAY))
        val ball=spinner(listOf("1 мм","2,5 мм","5 мм","10 мм"));p.addView(ball)
        p.addView(text("Нагрузка",14f,Color.LTGRAY));val load=spinner(listOf("—"));p.addView(load)
        val a=field("Отпечаток 1", "");val b=field("Отпечаток 2", "")
        labeled(p,"Отпечаток 1 (мм или деления)",a);labeled(p,"Отпечаток 2 (мм или деления)",b)
        clearButton(p,listOf(a,b));val out=text("",28f,accent);p.addView(out)
        val update = {
            if(a.text.isBlank()||b.text.isBlank()) out.text="" else out.text=try {
                val diameter=listOf(1.0,2.5,5.0,10.0)[ball.selectedItemPosition]
                val kgf=bLoads[load.selectedItemPosition.coerceIn(0,bLoads.lastIndex)]
                val newtons=units.selectedItemPosition==0
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
        val p=panel();p.addView(text("Минимальная толщина образца",22f))
        p.addView(text("Все нагрузки из справочных таблиц Excel v2.0",13f,Color.LTGRAY))
        val method=spinner(listOf("Роквелл (HRC)","Виккерс · ГОСТ Р ИСО 6507-4-2007","Виккерс · ГОСТ 2999-59","Виккерс · ГОСТ 9450-76","Бринелль · ГОСТ 9012-59"))
        p.addView(text("Метод и стандарт",14f,Color.LTGRAY));p.addView(method)
        val materialLabel=text("Материал (ГОСТ 9012-59, табл. 2)",14f,Color.LTGRAY);p.addView(materialLabel)
        val material=spinner(listOf("Сталь, чугун, высокопрочные сплавы","Титан и сплавы","Медь и лёгкие металлы, сплавы","Подшипниковые сплавы","Свинец, олово, мягкие металлы"));p.addView(material)
        val vMaterialLabel=text("Материал (таблицы Виккерса)",14f,Color.LTGRAY);p.addView(vMaterialLabel)
        val vMaterial=spinner(listOf("Сталь","Цветные металлы"));p.addView(vMaterial)
        val hardness=field("Введите прогнозируемую твёрдость", "")
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
            val headers=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            listOf("Шкала / шарик", "Нагрузка", "Мин. толщина", "").forEachIndexed { i,label ->
                headers.addView(text(label,10f,Color.LTGRAY),LinearLayout.LayoutParams(0,-2,listOf(1.25f,1.35f,1.25f,0.35f)[i]))
            }
            resultsContainer.addView(headers)
            rows.forEachIndexed { index, entry ->
                val d=Regex("D=([^·]+)").find(entry)?.groupValues?.get(1)?.trim()
                val f=Regex("F=([^·]+)").find(entry)?.groupValues?.get(1)?.trim()
                val thick=entry.substringAfterLast(": ","—")
                val scale=if(d!=null) d else entry.substringBefore(":")
                val loadValue=if(f!=null) f.substringBefore(" (") else "—"
                val cells=listOf(scale,loadValue,thick,"✓")
                val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(3),dp(5),dp(3),dp(5)); if(index%2==0) background=shape(Color.rgb(29,44,61)) }
                cells.forEachIndexed { i,value ->
                    val cell=text(value,if(i==3) 17f else 12f,if(i>=2) accent else Color.WHITE)
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
