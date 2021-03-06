package com.felipecsl.elifut;

import com.google.common.collect.FluentIterable;

import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.util.Log;

import com.felipecsl.elifut.models.Club;
import com.felipecsl.elifut.models.League;
import com.felipecsl.elifut.models.Player;
import com.felipecsl.elifut.preferences.JsonPreference;
import com.felipecsl.elifut.preferences.LeagueDetails;
import com.felipecsl.elifut.preferences.UserPreferences;
import com.felipecsl.elifut.services.ClubSquadBuilder;
import com.felipecsl.elifut.services.ElifutDataStore;
import com.felipecsl.elifut.services.ElifutService;
import com.felipecsl.elifut.services.ResponseBodyMapper;
import com.felipecsl.elifut.services.ResponseMapper;

import java.util.List;

import retrofit2.Response;
import rx.Observable;
import rx.schedulers.Schedulers;

/** Makes all the API calls and saves all the state and data needed for the app to start up */
public class AppInitializer {
  private static final String TAG = "AppInitializer";
  private final ElifutService service;
  private final LeagueDetails leagueDetails;
  private final JsonPreference<Club> clubPreference;
  private final JsonPreference<League> leaguePreference;
  private final ElifutDataStore persistenceService;
  private final SharedPreferences sharedPreferences;

  public AppInitializer(ElifutService service, UserPreferences userPreferences,
      LeagueDetails leagueDetails, ElifutDataStore persistenceService,
      SharedPreferences sharedPreferences) {
    this.service = service;
    this.leagueDetails = leagueDetails;
    this.persistenceService = persistenceService;
    this.sharedPreferences = sharedPreferences;
    this.clubPreference = userPreferences.clubPreference();
    this.leaguePreference = userPreferences.leaguePreference();
  }

  public Observable<Void> initialize(int nationId, ProgressDialog progressDialog) {
    return service.randomClub(nationId)
        .subscribeOn(Schedulers.io())
        .observeOn(Schedulers.io())
        .compose(this::transform)
        .doOnNext(i -> progressDialog.setProgress(15))
        .flatMap(this::loadLeague)
        .doOnNext(i -> progressDialog.setProgress(30))
        .flatMap(this::loadLeagueClubs)
        .doOnNext(i -> progressDialog.setProgress(45))
        .flatMap(Observable::from)
        .flatMap(this::loadPlayers)
        .filter(this::checkMinimumPlayers)
        .doOnNext(i -> progressDialog.setProgress(progressDialog.getProgress() + 2))
        .toList()
        .map(this::persistClubs)
        .flatMap(Observable::from)
        .map(this::persistPlayers)
        .map(ClubSquadBuilder::build)
        .map(persistenceService::create)
        .map(nothing -> (Void) null);
  }

  private List<ClubAndPlayers> persistClubs(List<ClubAndPlayers> clubAndPlayers) {
    leagueDetails.initialize(FluentIterable
        .from(clubAndPlayers)
        .transform(cp -> cp.club)
        .toList());
    return clubAndPlayers;
  }

  private boolean checkMinimumPlayers(ClubAndPlayers clubAndPlayers) {
    boolean hasMinimumPlayers = clubAndPlayers.players.size() > 11;
    if (!hasMinimumPlayers) {
      Log.w(TAG, String.format("Dropping club %s due to not having at least 11 players",
          clubAndPlayers.club));
    }
    return hasMinimumPlayers;
  }

  /** Clears all app settings and reinitializes state */
  public void clearData() {
    sharedPreferences.edit().clear().apply();
    persistenceService.deleteAll();
  }

  private Observable<? extends List<Club>> loadLeagueClubs(League league) {
    return service.clubsByLeague(leaguePreference.set(league).id())
        .compose(this::transform);
  }

  private Observable<? extends League> loadLeague(Club club) {
    return service.league(clubPreference.set(club).league_id()).compose(this::transform);
  }

  private ClubSquadBuilder persistPlayers(ClubAndPlayers clubAndPlayers) {
    persistenceService.create(FluentIterable.from(clubAndPlayers.players)
        .transform(players -> players.toBuilder().clubId(clubAndPlayers.club.id()).build())
        .toList());
    return new ClubSquadBuilder(clubAndPlayers.club, clubAndPlayers.players);
  }

  private Observable<? extends ClubAndPlayers> loadPlayers(Club club) {
    // Make sure players of type Team of the Week, for example, are not included in team squads,
    // which would be totally unfair
    return service.playersByClub(club.id())
        .compose(this::transform)
        .flatMap(Observable::from)
        .filter(this::checkValidPlayer)
        .toList()
        .map(players -> new ClubAndPlayers(club, players));
  }

  private boolean checkValidPlayer(Player player) {
    boolean validColor = player.isValidColor();
    if (!validColor) {
      Log.w(TAG, String.format(
          "Dropping player %s since color %s is not valid", player, player.color()));
    }
    return validColor;
  }

  private <T> Observable<T> transform(Observable<Response<T>> observable) {
    return observable.flatMap(ResponseMapper.<T>instance())
        .map(ResponseBodyMapper.<T>instance());
  }

  static class ClubAndPlayers {
    final Club club;
    final List<Player> players;

    ClubAndPlayers(Club club, List<Player> players) {
      this.club = club;
      this.players = players;
    }
  }
}
