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
        spinners.forEach { it.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,position:Int,id:Long) { update() }
            override fun onNothingSelected(p:android.widget.AdapterView<*>?) {}
        } };update()
    }
    private fun Double.pretty(): String = String.format(Locale("ru"),"%.2f",this)
    private fun EditText.num(): Double = text.toString().replace(',','.').toDouble()
    private fun vickersScreen() {
        val p=panel();p.addView(text("Твёрдость по Виккерсу",22f))
        val mode=spinner(listOf("Прямой ввод диагоналей, мм","Микроскоп X10","Микроскоп X20","Микротвёрдость (0,3 мкм/дел.)"));p.addView(mode)
        val units=spinner(listOf("Нагрузка, Н","Нагрузка, кгс"));p.addView(units)
        val a=field("Диагональ 1", "210");val b=field("Диагональ 2", "210");val load=field("Нагрузка", "49.03")
        listOf(a,b,load).forEach(p::addView)
        val out=text("",28f,accent);p.addView(out)
        watch(listOf(a,b,load),listOf(mode,units)) {
            out.text=try { "HV  ${Hardness.vickers(a.num(),b.num(),load.num(),mode.selectedItemPosition,units.selectedItemPosition==0).pretty()}" } catch(e:Exception) { "Проверьте введённые значения" }
        }
    }
    private fun brinellScreen() {
        val p=panel();p.addView(text("Твёрдость по Бринеллю",22f))
        val microscope=spinner(listOf("Диаметр отпечатка, мм","Отсчёт микроскопа (×0,01 мм)"));p.addView(microscope)
        val units=spinner(listOf("Нагрузка, Н","Нагрузка, кгс"));p.addView(units)
        val ball=field("Диаметр шарика, мм","5");val a=field("Отпечаток 1","2.2");val b=field("Отпечаток 2","2.2");val load=field("Нагрузка","1226")
        listOf(ball,a,b,load).forEach(p::addView)
        val out=text("",28f,accent);p.addView(out)
        watch(listOf(ball,a,b,load),listOf(microscope,units)) {
            out.text=try { "HB  ${Hardness.brinell(ball.num(),a.num(),b.num(),load.num(),units.selectedItemPosition==0,microscope.selectedItemPosition==1).pretty()}" } catch(e:Exception) { "Проверьте: 0 < отпечаток < шарик" }
        }
    }
    private fun thicknessScreen() {
        val p=panel(); p.addView(text("Минимальная толщина образца",22f))
        p.addView(text("По справочным таблицам Excel, версия 2.0",13f,Color.LTGRAY))
        val method=spinner(listOf("Роквелл (HRC)","Виккерс · ГОСТ Р ИСО 6507-4-2007","Виккерс · ГОСТ 2999-59","Виккерс · ГОСТ 9450-76","Бринелль · ГОСТ 9012-59"))
        p.addView(text("Метод и стандарт",14f,Color.LTGRAY));p.addView(method)
        val material=spinner(listOf("Сталь","Цветные металлы"))
        p.addView(text("Материал (для ГОСТ 2999 и 9450)",14f,Color.LTGRAY));p.addView(material)
        val load=spinner(listOf("HV0,2","HV0,3","HV0,5","HV1","HV5","HV10","HV20","HV30","HV50"))
        p.addView(text("Нагрузка Виккерса",14f,Color.LTGRAY));p.addView(load)
        val ball=spinner(listOf("2,5 мм","5 мм","10 мм"))
        val hbLoad=spinner(listOf("62,5 кгс","100 кгс","125 кгс","187,5 кгс","250 кгс","500 кгс","750 кгс","1000 кгс","1500 кгс","3000 кгс"))
        p.addView(text("Диаметр шарика Бринелля",14f,Color.LTGRAY));p.addView(ball)
        p.addView(text("Нагрузка Бринелля",14f,Color.LTGRAY));p.addView(hbLoad)
        val hb=field("Ожидаемая твёрдость HB", "330")
        val actual=field("Фактическая толщина, мм (необязательно)", "1.9")
        p.addView(hb);p.addView(actual)
        p.addView(text("В исходном файле HV = ROUND(HB × 1,05); HRC определяется по отдельной формуле.",12f,Color.LTGRAY))
        val out=text("",24f,accent);p.addView(out)
        val note=text("",13f,Color.LTGRAY);p.addView(note)
        watch(listOf(hb,actual),listOf(method,material,load,ball,hbLoad)) {
            val mode=method.selectedItemPosition
            material.visibility=if(mode==2 || mode==3) View.VISIBLE else View.GONE
            load.visibility=if(mode in 1..3) View.VISIBLE else View.GONE
            ball.visibility=if(mode==4) View.VISIBLE else View.GONE
            hbLoad.visibility=if(mode==4) View.VISIBLE else View.GONE
            try {
                val hardness=hb.num();require(hardness>0)
                val hv=floor(hardness*1.05+0.5)
                val hrc=if(hardness<=80) null else kotlin.math.round((hardness-80)/10.0*2)/2.0
                val hvNames=listOf("02","03","05","1","5","10","20","30","50")
                val index=load.selectedItemPosition
                val mat=if(material.selectedItemPosition==0) "steel" else "nonferrous"
                val key=when(mode) {
                    0 -> "rockwell"
                    1 -> "iso"+hvNames[index]
                    2 -> "2999_"+mat+"_"+listOf("1","5","10","20","30").getOrNull(index-3).orEmpty()
                    3 -> "9450_"+mat+"_"+listOf("02","05").getOrNull(if(index==0) 0 else if(index==2) 1 else -1).orEmpty()
                    else -> "hb_"+listOf("2.5","5","10")[ball.selectedItemPosition]+"_"+listOf("62.5","100","125","187.5","250","500","750","1000","1500","3000")[hbLoad.selectedItemPosition]
                }
                val supported=when(mode) { 2 -> index in 3..7; 3 -> index==0 || index==2; else -> true }
                val value=if(!supported) null else thicknessTables.lookup(key, when(mode){0->hrc ?: -1.0;4->hardness;else->hv},mode==0)
                if(value==null || !value.isFinite() || value<=0) {
                    out.text="Нет табличного значения"
                    note.text="Выбранное сочетание не поддерживается таблицей или выходит за диапазон. Для Бринелля дополнительно проверьте ограничения ГОСТ и исходного Excel."
                } else {
                    out.text="Минимум: ${value.pretty()} мм"
                    val actualValue=actual.text.toString().replace(',','.').toDoubleOrNull()
                    note.text="HB ${hardness.pretty()} · HV ${hv.pretty()}"+(if(actualValue!=null) "\nОбразец: "+(if(actualValue>=value) "толщина достаточна" else "толщина недостаточна") else "")+"\nТабличный результат; проверяйте применимость метода и нагрузки."
                }
            } catch (_: Exception) {out.text="Введите положительную твёрдость HB";note.text=""}
        }
    }
}
