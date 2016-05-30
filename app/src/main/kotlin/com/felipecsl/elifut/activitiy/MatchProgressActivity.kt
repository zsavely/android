package com.felipecsl.elifut.activitiy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.annotation.DrawableRes
import android.support.design.widget.FloatingActionButton
import android.support.design.widget.Snackbar
import android.support.v4.content.ContextCompat
import android.support.v7.widget.Toolbar
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import butterknife.*
import com.felipecsl.elifut.BuildConfig
import com.felipecsl.elifut.R
import com.felipecsl.elifut.ResponseObserver
import com.felipecsl.elifut.match.MatchResultController
import com.felipecsl.elifut.models.*
import com.felipecsl.elifut.preferences.LeagueDetails
import com.felipecsl.elifut.preferences.UserPreferences
import com.felipecsl.elifut.widget.FractionView
import com.squareup.picasso.Picasso
import icepick.State
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.functions.Func1
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MatchProgressActivity : ElifutActivity() {
  @BindView(R.id.toolbar) lateinit var toolbar: Toolbar
  @BindView(R.id.img_team_home) lateinit var imgTeamHome: ImageView
  @BindView(R.id.img_team_away) lateinit var imgTeamAway: ImageView
  @BindView(R.id.txt_team_home) lateinit var txtTeamHome: TextView
  @BindView(R.id.txt_team_away) lateinit var txtTeamAway: TextView
  @BindView(R.id.txt_team_home_goals) lateinit var txtTeamHomeGoals: TextView
  @BindView(R.id.events_layout) lateinit var eventsLayout: LinearLayout
  @BindView(R.id.txt_team_away_goals) lateinit var txtTeamAwayGoals: TextView
  @BindView(R.id.fractionView) lateinit var fractionView: FractionView
  @BindView(R.id.fab_play_pause) lateinit var playPauseButton: FloatingActionButton
  @BindView(R.id.fab_done) lateinit var doneButton: FloatingActionButton
  @BindString(R.string.end_first_half) lateinit var strEndOfFirstHalf: String
  @BindString(R.string.end_match) lateinit var strEndOfMatch: String
  @JvmField @BindDimen(R.dimen.match_event_icon_size) var iconSize: Int = 0
  @JvmField @BindDimen(R.dimen.match_event_icon_padding) var iconPadding: Int = 0

  @Inject lateinit var leagueDetails: LeagueDetails

  @State internal var round: LeagueRound? = null
  @State internal var isRunning: Boolean = false
  @State internal var elapsedMinutes: Int = 0

  private val subscriptions = CompositeSubscription()
  private var finalScoreMessage: String? = null
  private var finalScoreIcon: Int = 0
  private var match: Match? = null
  private var userClub: Club? = null
  private var matchResult: MatchResult? = null
  private val observer = object : ResponseObserver<Club>(this, TAG, "Failed to load club") {
    override fun onNext(response: Club) {
      fillClubInfos(response)
    }

    override fun onCompleted() {
      if (!matchResult!!.isDraw) {
        val winner = matchResult!!.winner()
        //noinspection ConstantConditions
        val isWinner = winner!!.nameEquals(userClub)
        finalScoreIcon = if (isWinner)
          R.drawable.ic_mood_black_48px
        else
          R.drawable.ic_sentiment_very_dissatisfied_black_48px
        if (isWinner) {
          finalScoreMessage = "Winner!"
        } else {
          finalScoreMessage = "Defeated."
        }
      } else {
        finalScoreIcon = R.drawable.ic_sentiment_neutral_black_48px
        finalScoreMessage = "Draw."
      }
      if (BuildConfig.DEBUG) {
        Log.d(TAG, finalScoreMessage)
      }

      startTimer()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    setContentView(R.layout.activity_match_progress)
    ButterKnife.bind(this)
    daggerComponent().inject(this)
    setSupportActionBar(toolbar)

    if (savedInstanceState == null) {
      val intent = intent
      round = intent.getParcelableExtra<LeagueRound>(EXTRA_ROUND)
    }
    userClub = userPreferences!!.club()
    match = round!!.findMatchByClub(userClub)
    matchResult = match!!.result()
    loadClubs(match!!.home().id(), match!!.away().id())
  }

  override fun onDestroy() {
    super.onDestroy()
    stopTimer()
    val controller = MatchResultController(userPreferences)
    controller.updateWithResult(matchResult)
  }

  private fun loadClubs(homeId: Int, awayId: Int) {
    val subscription = clubObservable(homeId).mergeWith(clubObservable(awayId)).subscribe(observer)

    subscriptions.add(subscription)
  }

  private fun clubObservable(id: Int): Observable<Club> {
    return service.club(id).compose(this.applyTransformations<Club>())
  }

  private fun fillClubInfos(club: Club) {
    val imgView: ImageView
    val txtView: TextView
    if (club.nameEquals(match!!.home())) {
      imgView = imgTeamHome
      txtView = txtTeamHome
    } else {
      imgView = imgTeamAway
      txtView = txtTeamAway
    }
    Picasso.with(this@MatchProgressActivity).load(club.large_image()).into(imgView)

    txtView.text = club.tinyName().toUpperCase()
  }

  private fun stopTimer() {
    subscriptions.clear()
    isRunning = false
  }

  private fun startTimer() {
    subscriptions.add(matchResult!!.eventsObservable(elapsedMinutes)
        .map<Goal>(Func1 { matchEvent -> matchEvent as Goal })
        .observeOn(AndroidSchedulers.mainThread()).subscribe { goal ->
      val txtScore = if (goal.club().nameEquals(match!!.home()))
        txtTeamHomeGoals
      else
        txtTeamAwayGoals
      var currGoals = Integer.parseInt(txtScore.text.toString())
      txtScore.text = (++currGoals).toString()
      appendEvent(R.drawable.ball,
          "${goal.time()}' ${goal.club().abbrev_name()} ${goal.player().name()}")
    })

    subscriptions.add(timerObservable().observeOn(AndroidSchedulers.mainThread()).subscribe { l ->
      elapsedMinutes++
      fractionView.setFraction(elapsedMinutes % 45, 60)
      if (elapsedMinutes == 45) {
        appendEvent(R.drawable.ic_schedule_black_48px, strEndOfFirstHalf)
      } else if (elapsedMinutes == 90) {
        stopTimer()
        appendEvent(R.drawable.ic_schedule_black_48px, strEndOfMatch)
        appendEvent(finalScoreIcon, finalScoreMessage as String)
        val winner = matchResult!!.winner()
        val isDraw = matchResult!!.isDraw
        val isWinner = !isDraw && userClub!!.nameEquals(winner)
        if (isDraw || isWinner) {
          appendEvent(R.drawable.ic_attach_money_black_24dp, "+" +
              if (isWinner) UserPreferences.COINS_PRIZE_WIN else UserPreferences.COINS_PRIZE_DRAW)
        }
        playPauseButton.visibility = View.GONE
        doneButton.visibility = View.VISIBLE
        fractionView.setFraction(45, 60)
      }
    })
    isRunning = true
  }

  private fun timerObservable(): Observable<Long> {
    return if (BuildConfig.DEBUG)
      Observable.interval(0, 100, TimeUnit.MILLISECONDS)
    else
      Observable.interval(0, 1, TimeUnit.SECONDS)
  }

  private fun appendEvent(@DrawableRes icon: Int, text: String) {
    val view = LayoutInflater.from(this)
        .inflate(R.layout.match_event, eventsLayout, false) as TextView
    view.text = text
    val drawable = ContextCompat.getDrawable(this, icon)
    drawable.setBounds(0, 0, iconSize, iconSize)
    view.compoundDrawablePadding = iconPadding
    view.setCompoundDrawables(drawable, null, null, null)
    eventsLayout.addView(view, 0)
  }

  @OnClick(R.id.fab_play_pause) fun onClickPause() {
    if (isRunning) {
      stopTimer()
      Snackbar.make(playPauseButton, R.string.match_paused, Snackbar.LENGTH_SHORT).show()
      playPauseButton.setImageResource(R.drawable.ic_play_arrow_white_48dp)
    } else {
      startTimer()
      Snackbar.make(playPauseButton, R.string.match_resumed, Snackbar.LENGTH_SHORT).show()
      playPauseButton.setImageResource(R.drawable.ic_pause_white_48dp)
    }
  }

  @OnClick(R.id.fab_done) fun onClickDone() {
    finish()
    startActivity(LeagueRoundResultsActivity.newIntent(this, round))
  }

  companion object {
    private val TAG = MatchProgressActivity::class.java.simpleName
    private val EXTRA_ROUND = "EXTRA_ROUND"

    fun newIntent(context: Context, round: LeagueRound): Intent {
      return Intent(context, MatchProgressActivity::class.java).putExtra(EXTRA_ROUND, round)
    }
  }
}
