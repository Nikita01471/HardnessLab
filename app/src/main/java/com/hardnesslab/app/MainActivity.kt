package com.hardnesslab.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
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
        p.addView(text(label, 14f, Color.LTGRAY)); p.addView(input)
    }
    private fun clearButton(p: LinearLayout, fields: List<EditText>) {
        p.addView(Button(this).apply { text="Очистить"; isAllCaps=false; setOnClickListener { fields.forEach { it.text.clear() } } })
    }
    private var currentTab = 0
    private lateinit var thicknessTables: ThicknessTables
    private fun text(s: String, size: Float = 17f, color: Int = Color.WHITE): TextView = TextView(this).apply {
        text=s; textSize=size; setTextColor(color); setPadding(0,12,0,12)
    }
    private fun field(hintText: String, initial: String): EditText = EditText(this).apply {
        hint=hintText; setText(initial); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setSingleLine(true)
    }
    private fun spinner(options: List<String>): Spinner = Spinner(this).apply {
        adapter=ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
    }
    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(24,18,24,22); setBackgroundColor(panel)
        val lp=LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,14,0,12); root.addView(this,lp)
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); thicknessTables=ThicknessTables(this); showScreen(0) }
    private fun showScreen(tab: Int) {
        currentTab=tab
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,26,24,24); setBackgroundColor(bg) }
        val scroll=ScrollView(this).apply { addView(root) }; setContentView(scroll)
        root.addView(text("HARDNESS LAB",26f,accent).apply { setTypeface(null,Typeface.BOLD) })
        root.addView(text("Расчёт твёрдости материалов",14f,Color.LTGRAY))
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL };root.addView(tabs)
        listOf("Виккерс","Бринелль","Толщина").forEachIndexed { i,label ->
            tabs.addView(Button(this).apply { text=label; isAllCaps=false; setTextColor(if(i==tab) accent else Color.WHITE); setOnClickListener { showScreen(i) } },LinearLayout.LayoutParams(0,-2,1f))
        }
        when(tab) { 0 -> vickersScreen(); 1 -> brinellScreen(); else -> thicknessScreen() }
        root.addView(text("Офлайн • Формулы из Excel v.2",12f,Color.GRAY))
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
        watch(listOf(a,b),listOf(microscope,units,ball)) { loadMenu(bLoads,units,load,"b_load",update) }
    }
    private fun thicknessScreen() {
        val p=panel();p.addView(text("Минимальная толщина образца",22f))
        p.addView(text("Справочные таблицы Excel v2.0",13f,Color.LTGRAY))
        val method=spinner(listOf("Роквелл (HRC)","Виккерс · ГОСТ Р ИСО 6507-4-2007","Виккерс · ГОСТ 2999-59","Виккерс · ГОСТ 9450-76","Бринелль · ГОСТ 9012-59"))
        p.addView(text("Метод и стандарт",14f,Color.LTGRAY));p.addView(method)
        val materialLabel=text("Материал",14f,Color.LTGRAY);p.addView(materialLabel)
        val material=spinner(listOf("Сталь","Цветные металлы"));p.addView(material)
        val loadLabel=text("Шкала Виккерса",14f,Color.LTGRAY);p.addView(loadLabel)
        val hvLoad=spinner(listOf("HV0,2","HV0,3","HV0,5","HV1","HV5","HV10","HV20","HV30","HV50"));p.addView(hvLoad)
        val ballLabel=text("Диаметр шарика Бринелля",14f,Color.LTGRAY);p.addView(ballLabel)
        val ball=spinner(listOf("2,5 мм","5 мм","10 мм"));p.addView(ball)
        val hbLoadLabel=text("Нагрузка Бринелля",14f,Color.LTGRAY);p.addView(hbLoadLabel)
        val hbLoad=spinner(listOf("62,5 кгс","100 кгс","125 кгс","187,5 кгс","250 кгс","500 кгс","750 кгс","1000 кгс","1500 кгс","3000 кгс"));p.addView(hbLoad)
        val hardness=field("Введите твёрдость", "");val hardnessLabel=text("Твёрдость HRC",14f,Color.LTGRAY)
        p.addView(hardnessLabel);p.addView(hardness);clearButton(p,listOf(hardness))
        val out=text("",24f,accent);p.addView(out)
        val note=text("",13f,Color.LTGRAY);p.addView(note)
        watch(listOf(hardness),listOf(method,material,hvLoad,ball,hbLoad)) {
            val m=method.selectedItemPosition
            val matVisible=m==2||m==3
            material.visibility=if(matVisible) View.VISIBLE else View.GONE;materialLabel.visibility=material.visibility
            hvLoad.visibility=if(m in 1..3) View.VISIBLE else View.GONE;loadLabel.visibility=hvLoad.visibility
            ball.visibility=if(m==4) View.VISIBLE else View.GONE;ballLabel.visibility=ball.visibility
            hbLoad.visibility=ball.visibility;hbLoadLabel.visibility=ball.visibility
            hardnessLabel.text="Твёрдость "+when(m){0->"HRC";4->"HB";else->"HV"}
            if(hardness.text.isBlank()) {out.text="";note.text="";return@watch}
            val value=hardness.text.toString().replace(',','.').toDoubleOrNull()
            if(value==null||!value.isFinite()||value<=0){out.text="Введите положительное значение";note.text="";return@watch}
            val idx=hvLoad.selectedItemPosition
            val mat=if(material.selectedItemPosition==0) "steel" else "nonferrous"
            val key=when(m){
                0->"rockwell"
                1->"iso"+listOf("02","03","05","1","5","10","20","30","50")[idx]
                2->"2999_"+mat+"_"+listOf("1","5","10","20","30").getOrNull(idx-3).orEmpty()
                3->"9450_"+mat+"_"+listOf("02","05").getOrNull(if(idx==0)0 else if(idx==2)1 else -1).orEmpty()
                else->"hb_"+listOf("2.5","5","10")[ball.selectedItemPosition]+"_"+listOf("62.5","100","125","187.5","250","500","750","1000","1500","3000")[hbLoad.selectedItemPosition]
            }
            val supported=when(m){2->idx in 3..7;3->idx==0||idx==2;else->true}
            val result=if(supported) thicknessTables.lookup(key,value,m==0) else null
            if(result!=null && result.isFinite() && result>0){out.text="Минимум: ${result.pretty()} мм";note.text="Значение из справочной таблицы выбранного метода"}
            else {out.text="Нет табличного значения";note.text="Проверьте шкалу, диапазон твёрдости и параметры испытания"}
        }
    }
}
