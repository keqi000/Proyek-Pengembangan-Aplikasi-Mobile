package com.example.prediksibola

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.InputStreamReader
import android.view.View
import java.nio.channels.FileChannel
import com.opencsv.CSVReader
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var tvMatchPrediction: TextView
    private lateinit var tvTeam1Stats: TextView
    private lateinit var tvTeam2Stats: TextView
    private lateinit var tvTeam1Title: TextView
    private lateinit var tvTeam2Title: TextView
    private lateinit var tvRecentForm: TextView
    private lateinit var spinnerTeam1: Spinner
    private lateinit var spinnerTeam2: Spinner
    private lateinit var btnPredict: Button
    private lateinit var barChart: BarChart
    private lateinit var tflite: Interpreter
    private lateinit var matches: List<Match>
    private val featureColumns = listOf(
        "venue_code", "opp_code", "hour", "day_code",
        "goals_scored", "goals_conceded", "shots_ratio"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadMatchData()
        initTfliteInterpreter()
        setupSpinners()
        setupPredictButton()
    }

    private fun initViews() {
        spinnerTeam1 = findViewById(R.id.spinnerTeam1)
        spinnerTeam2 = findViewById(R.id.spinnerTeam2)
        btnPredict = findViewById(R.id.btnPredict)
        barChart = findViewById(R.id.barChart)
        tvMatchPrediction = findViewById(R.id.tvMatchPrediction)
        tvTeam1Stats = findViewById(R.id.tvTeam1Stats)
        tvTeam2Stats = findViewById(R.id.tvTeam2Stats)
        tvRecentForm = findViewById(R.id.tvRecentForm)
        tvTeam1Title = findViewById(R.id.tvTeam1Title)
        tvTeam2Title = findViewById(R.id.tvTeam2Title)
    }

    private fun loadMatchData() {
        matches = assets.open("matches.csv").use { inputStream ->
            CSVReader(InputStreamReader(inputStream)).readAll().drop(1).map { row ->
                Match(
                    team = row[27].trim(),
                    opponent = row[10].trim(),
                    venue = row[6].trim(),
                    date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(row[1].trim()) ?: Date(),
                    result = row[7].trim(),
                    goalsScored = row[8].trim().toFloatOrNull() ?: 0f,
                    goalsConceded = row[9].trim().toFloatOrNull() ?: 0f,
                    shots = row[20].trim().toFloatOrNull() ?: 0f,
                    shotsOnTarget = row[21].trim().toFloatOrNull() ?: 1f
                )
            }
        }
    }

    private fun prepareTeamData(teamName: String, opponentName: String, venueCode: Int): Array<FloatArray> {
        val features = mutableMapOf<String, Float>()

        // Map all features to their values
        features[featureColumns[0]] = venueCode.toFloat() // venue_code
        features[featureColumns[1]] = matches.map { it.team }
            .distinct()
            .sorted()
            .indexOf(opponentName)
            .toFloat() // opp_code
        features[featureColumns[2]] = 16f // hour
        features[featureColumns[3]] = 6f // day_code

        val teamMatches = matches.filter { it.team == teamName }
        val recentMatches = teamMatches.takeLast(5)

        features[featureColumns[4]] = recentMatches.map { it.goalsScored }.average().toFloat() // goals_scored
        features[featureColumns[5]] = recentMatches.map { it.goalsConceded }.average().toFloat() // goals_conceded
        features[featureColumns[6]] = recentMatches.map { it.shots / it.shotsOnTarget }.average().toFloat() // shots_ratio

        // Return features in the same order as featureColumns
        return Array(1) {
            featureColumns.map { features[it] ?: 0f }.toFloatArray()
        }
    }

    private fun makePrediction(team1: String, team2: String) {
        val team1Data = prepareTeamData(team1, team2, 0)
        val team2Data = prepareTeamData(team2, team1, 1)

        val outputArray1 = Array(1) { FloatArray(2) }
        val outputArray2 = Array(1) { FloatArray(2) }

        tflite.run(team1Data, outputArray1)
        tflite.run(team2Data, outputArray2)

        val team1Prob = outputArray1[0][1]
        val team2Prob = outputArray2[0][1]

        val totalProb = team1Prob + team2Prob
        val team1Normalized = (team1Prob / totalProb) * 100
        val team2Normalized = (team2Prob / totalProb) * 100

        val team1Stats = getTeamStats(team1)
        val team2Stats = getTeamStats(team2)

        displayResults(
            team1, team2,
            team1Normalized, team2Normalized,
            team1Stats, team2Stats
        )
    }

    private fun getTeamStats(teamName: String): TeamStats {
        val teamMatches = matches.filter { it.team == teamName }
        return TeamStats(
            goalsScored = teamMatches.map { it.goalsScored }.average().toFloat(),
            goalsConceded = teamMatches.map { it.goalsConceded }.average().toFloat(),
            shotsRatio = teamMatches.map { it.shots / it.shotsOnTarget }.average().toFloat()
        )
    }

    private fun setupSpinners() {
        val teams = matches.map { it.team }.distinct().sorted()
        val adapter1 = ArrayAdapter(this, android.R.layout.simple_spinner_item, teams)
        val adapter2 = ArrayAdapter(this, android.R.layout.simple_spinner_item, teams)

        adapter1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        adapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerTeam1.adapter = adapter1
        spinnerTeam2.adapter = adapter2

        // Add listeners to prevent selecting same team
        spinnerTeam1.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedTeam = spinnerTeam1.selectedItem.toString()
                if (selectedTeam == spinnerTeam2.selectedItem.toString()) {
                    // Select different team in spinner 2
                    val otherPosition = (position + 1) % teams.size
                    spinnerTeam2.setSelection(otherPosition)
                }
                btnPredict.isEnabled = spinnerTeam1.selectedItem.toString() != spinnerTeam2.selectedItem.toString()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTeam2.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedTeam = spinnerTeam2.selectedItem.toString()
                if (selectedTeam == spinnerTeam1.selectedItem.toString()) {
                    // Select different team in spinner 1
                    val otherPosition = (position + 1) % teams.size
                    spinnerTeam1.setSelection(otherPosition)
                }
                btnPredict.isEnabled = spinnerTeam1.selectedItem.toString() != spinnerTeam2.selectedItem.toString()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }


    private fun setupPredictButton() {
        btnPredict.setOnClickListener {
            val team1 = spinnerTeam1.selectedItem.toString()
            val team2 = spinnerTeam2.selectedItem.toString()
            makePrediction(team1, team2)
        }
    }

    private fun displayResults(
        team1: String, team2: String,
        team1Prob: Float, team2Prob: Float,
        team1Stats: TeamStats, team2Stats: TeamStats
    ) {
        val predictedWinner = if (team1Prob > team2Prob) team1 else team2
        val team1Form = calculateTeamForm(team1)
        val team2Form = calculateTeamForm(team2)

        // Match Prediction
        val matchPredictionText = String.format(Locale.US, """
        Match Prediction
        Probability of %s winning: %.2f%%
        Probability of %s winning: %.2f%%
        🏆 %s diprediksi akan memenangkan pertandingan!
    """.trimIndent(), team1, team1Prob, team2, team2Prob, predictedWinner)

        // Team 1 Stats
        val team1StatsText = String.format(Locale.US, """
        %s Stats (Overall Average)
        Goals Scored: %.2f
        Goals Conceded: %.2f
        Shots Ratio: %.2f
        Venue: %s
        Most Common Match Hour: %s
        Most Common Day: %s
    """.trimIndent(), team1, team1Stats.goalsScored, team1Stats.goalsConceded, team1Stats.shotsRatio,
            getCommonVenue(team1), getMostCommonHour(team1), getMostCommonDay(team1))

        // Team 2 Stats
        val team2StatsText = String.format(Locale.US, """
        %s Stats (Overall Average)
        Goals Scored: %.2f
        Goals Conceded: %.2f
        Shots Ratio: %.2f
        Venue: %s
        Most Common Match Hour: %s
        Most Common Day: %s
    """.trimIndent(), team2, team2Stats.goalsScored, team2Stats.goalsConceded, team2Stats.shotsRatio,
            getCommonVenue(team2), getMostCommonHour(team2), getMostCommonDay(team2))

        // Recent Form
        val recentFormText = String.format(Locale.US, """
        Recent Team Form (Last 5 Matches)
        %s
        Wins: %d | Draws: %d | Losses: %d
        Form: %s
        
        %s
        Wins: %d | Draws: %d | Losses: %d
        Form: %s
    """.trimIndent(), team1, team1Form.wins, team1Form.draws, team1Form.losses, team1Form.formString,
            team2, team2Form.wins, team2Form.draws, team2Form.losses, team2Form.formString)

        // Set the text to the respective TextViews
        findViewById<TextView>(R.id.tvMatchPrediction).text = matchPredictionText
        findViewById<TextView>(R.id.tvTeam1Stats).text = team1StatsText
        findViewById<TextView>(R.id.tvTeam2Stats).text = team2StatsText
        findViewById<TextView>(R.id.tvRecentForm).text = recentFormText
        findViewById<TextView>(R.id.tvTeam1Title).text = "$team1 Stats"
        findViewById<TextView>(R.id.tvTeam2Title).text = "$team2 Stats"


        // Update the chart
        updateChart(team1, team2, floatArrayOf(team1Prob, team2Prob))
    }


    data class TeamForm(
        val wins: Int,
        val draws: Int,
        val losses: Int,
        val formString: String
    )

    private fun calculateTeamForm(teamName: String): TeamForm {
        val recentMatches = matches.filter { it.team == teamName }.takeLast(5)
        val wins = recentMatches.count { it.result == "W" }
        val draws = recentMatches.count { it.result == "D" }
        val losses = recentMatches.count { it.result == "L" }

        val formString = recentMatches.map { match ->
            when (match.result) {
                "W" -> "W"
                "D" -> "D"
                "L" -> "L"
                else -> "-"
            }
        }.joinToString(" ")

        return TeamForm(wins, draws, losses, formString)
    }

    private fun getCommonVenue(teamName: String): String {
        return matches.filter { it.team == teamName }
            .groupBy { it.venue }
            .maxByOrNull { it.value.size }?.key ?: "Unknown"
    }

    private fun getMostCommonHour(teamName: String): String {
        val calendar = Calendar.getInstance()
        return matches.filter { it.team == teamName }
            .map { match ->
                calendar.time = match.date
                calendar.get(Calendar.HOUR_OF_DAY)
            }
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key
            ?.let { String.format(Locale.US, "%02d:00", it) }
            ?: "16:00"
    }

    private fun getMostCommonDay(teamName: String): String {
        val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val calendar = Calendar.getInstance()
        return matches.filter { it.team == teamName }
            .map { match ->
                calendar.time = match.date
                calendar.get(Calendar.DAY_OF_WEEK) - 1
            }
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key
            ?.let { days[it] }
            ?: "Saturday"
    }


    private fun updateChart(team1: String, team2: String, probabilities: FloatArray) {
        val entries1 = ArrayList<BarEntry>().apply { add(BarEntry(0f, probabilities[0])) }
        val entries2 = ArrayList<BarEntry>().apply { add(BarEntry(1f, probabilities[1])) }

        val dataSet1 = BarDataSet(entries1, team1).apply {
            color = ContextCompat.getColor(this@MainActivity, R.color.background_content)
            valueTextSize = 14f
            valueTextColor = ContextCompat.getColor(this@MainActivity, R.color.text_color)
        }

        val dataSet2 = BarDataSet(entries2, team2).apply {
            color = ContextCompat.getColor(this@MainActivity, R.color.background_main)
            valueTextSize = 14f
            valueTextColor = ContextCompat.getColor(this@MainActivity, R.color.text_color)
        }

        barChart.apply {
            data = BarData(dataSet1, dataSet2)
            description.isEnabled = false
            legend.textSize = 12f
            legend.textColor = ContextCompat.getColor(this@MainActivity, R.color.text_color)
            legend.isEnabled = true
            xAxis.setDrawGridLines(false)
            xAxis.textColor = ContextCompat.getColor(this@MainActivity, R.color.text_color)
            axisLeft.textColor = ContextCompat.getColor(this@MainActivity, R.color.text_color)
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(1000)
            invalidate()
        }
    }



    private fun initTfliteInterpreter() {
        val modelBuffer = assets.openFd("model.tflite").use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
        tflite = Interpreter(modelBuffer)
    }
}

data class Match(
    val team: String,
    val opponent: String,
    val venue: String,
    val date: Date,
    val result: String,
    val goalsScored: Float,
    val goalsConceded: Float,
    val shots: Float,
    val shotsOnTarget: Float
)

data class TeamStats(
    val goalsScored: Float,
    val goalsConceded: Float,
    val shotsRatio: Float
)
