package com.edtech.plugtify.service;

import com.edtech.plugtify.config.ApplicationProperties;
import com.edtech.plugtify.domain.Token;
import com.edtech.plugtify.domain.User;
import com.edtech.plugtify.repository.TokenRepository;
import com.edtech.plugtify.repository.UserRepository;
import com.edtech.plugtify.service.dto.*;
import com.edtech.plugtify.web.rest.errors.InternalServerErrorException;
import com.edtech.plugtify.web.rest.errors.UserNotFoundException;
import org.springframework.http.*;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.swing.text.html.Option;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SpotifyService {

    private ApplicationProperties applicationProperties;
    private UserService userService;
    private UserRepository userRepository;
    private TokenRepository tokenRepository;

    public SpotifyService(
        ApplicationProperties applicationProperties,
        UserService userService,
        UserRepository userRepository,
        TokenRepository tokenRepository
    ) {
        this.applicationProperties = applicationProperties;
        this.userService = userService;
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
    }


    public void processAuthorizationCode(AuthorizationCodeDTO authorizationCode) throws Exception {

        HttpHeaders httpHeaders = this.getHttpHeadersAuth();

        MultiValueMap<String, String> parameterMap = new LinkedMultiValueMap<>();

        parameterMap.add("grant_type", authorizationCode.getGrant_type());
        parameterMap.add("code", authorizationCode.getCode());
        parameterMap.add("redirect_uri", authorizationCode.getRedirect_uri());

        HttpEntity<MultiValueMap<String, String>> httpEntity =
                new HttpEntity<>(parameterMap, httpHeaders);

        ResponseEntity<TokenDTO> newTokenResponse =
                this.getTokenDTOAuthAndRefresh(SpotifyConstants.URL_EXCHANGE_TOKEN, httpEntity);

        if (this.userService.getCurrentUser().isEmpty()) {
            throw new UserNotFoundException();
        }

        // get current user
        User actualUser = this.userService.getCurrentUser().get();

        // check if user have a token
        if (actualUser.getHasToken()) {
            // user have token; delete token
            actualUser.setToken(null);
            actualUser.setHasToken(false);
            this.userRepository.save(actualUser); // because the class is @Transactional we can do this
        }

        if(!newTokenResponse.hasBody()) {
            throw new Exception("newTokenResponse doesnt have body");
        }

        try {

            // the response body
            TokenDTO tokenDTORes = newTokenResponse.getBody();

            // set new token to user
            Token newToken = new Token();
            assert tokenDTORes != null;
            newToken.setAccess_token(tokenDTORes.getAccess_token());
            newToken.setExpires_in(tokenDTORes.getExpires_in());
            newToken.setRefresh_token(tokenDTORes.getRefresh_token());
            newToken.setScope(tokenDTORes.getScope());
            newToken.setToken_type(tokenDTORes.getToken_type());
            newToken.setLastUpdateTime(Timestamp.from(Instant.now()));

            actualUser.setToken(newToken);

            // set true for user has token
            actualUser.setHasToken(true);

            // update current user with its tokens
            this.userRepository.save(actualUser);

        } catch (Exception e) {
            throw new Exception(e);
        }
    }

    /**
     * Method to unfollow the current user for the given playlist
     * @return ResponseEntity
     */
    public ResponseEntity<Void> unfollowPlaylist(String principalName) {
        Optional<User> user = this.userRepository.findOneByLogin(principalName);

        if(user.isEmpty()) {
            throw new UserNotFoundException();
        }

        if(user.get().getToken() != null && user.get().getPlaylistId() != null) {
            Token userToken = user.get().getToken();

            if(this.isTokenExpired(userToken)) {
                this.refreshAccessToken(userToken);
            }

            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set("Authorization", userToken.getToken_type() + " " + userToken.getAccess_token());

            HttpEntity httpEntity = new HttpEntity(httpHeaders);

            Map<String, String> parametersMap = new HashMap<>();
            parametersMap.put("playlist_id", URLEncoder.encode(user.get().getPlaylistId(), StandardCharsets.UTF_8));

            UriComponentsBuilder uriComponentsBuilder =
                    UriComponentsBuilder.fromUriString(SpotifyConstants.URL_UNFOLLOW_PLAYLIST);

            RestTemplate restTemplate = new RestTemplate();
            return restTemplate.exchange(uriComponentsBuilder.buildAndExpand(parametersMap).toUriString(), HttpMethod.DELETE, httpEntity, Void.class);
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    /**
     * check if user has a playlist
     * @param tracks track to add
     * @return the response
     */
    public ResponseEntity<Void> addPlaylist(SpotifyTrackDTO[] tracks) {
        Optional<User> userOptional = this.userService.getCurrentUser();

        return userOptional.map(user -> {
            Token userToken = user.getToken();

            if(this.isTokenExpired(userToken)) {
                this.refreshAccessToken(userToken);
            }

            if(user.getPlaylistId() != null) {
                ResponseEntity<Void> response;

                response = this.replaceTrackPlaylist(tracks, user.getPlaylistId(), userToken);

                // validate if the tracks were replaced: for example the playlist was deleted
                if(response.getStatusCodeValue() == 404 || response.getStatusCodeValue() == 304) {
                    return this.createPlaylist(tracks, user, userToken);
                }

                return response;
            } else {
                return this.createPlaylist(tracks, user, userToken);
            }
        }).orElseThrow(UserNotFoundException::new);
    }

    /**
     * Method to replace tracks in playlist
     * @param tracks tracks to add to the playlist
     * @param playlistId the playlist id
     * @return response
     */
    public ResponseEntity<Void> replaceTrackPlaylist(SpotifyTrackDTO[] tracks, String playlistId, Token userToken) {

        SpotifyTrackDTO[] tracksLocal = new ArrayList<>(Arrays.asList(tracks)).subList(0, 40).toArray(SpotifyTrackDTO[]::new);

        String value = userToken.getToken_type() + " " + userToken.getAccess_token();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", value);
        headers.add("Content-Type", "application/json");

        Map<String, String> paramsReplaceTracks = new HashMap<>();
        paramsReplaceTracks.put("playlist_id", URLEncoder.encode(playlistId, StandardCharsets.UTF_8));

        String uris = Arrays.stream(tracksLocal).map(SpotifyTrackDTO::getUri).collect(Collectors.joining(","));

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(SpotifyConstants.URL_REPLACE_PLAYLIST)
                .queryParam("uris", uris);

        HttpEntity httpEntityReplace = new HttpEntity(headers);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setMessageConverters(this.getMessageConverters());

        ResponseEntity<Void> res = restTemplate.exchange(builder.buildAndExpand(paramsReplaceTracks).toUriString(), HttpMethod.PUT, httpEntityReplace, Void.class);

        return new ResponseEntity<>(res.getStatusCode());
    }

    /**
     * Method to create a playlist
     * @param tracks tracks to add to the playlist
     * @param user user owner of the playlist
     * @param userToken tokens
     * @return ResponseEntity
     */
    public ResponseEntity<Void> createPlaylist(SpotifyTrackDTO[] tracks, User user, Token userToken) {

        // creating playlist --> POST
        String value = userToken.getToken_type() + " " + userToken.getAccess_token();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", value);

        SpotifyPlaylistRequest playlistRequest = new SpotifyPlaylistRequest("Plugtify Playlist", "Playlist created with Plugtify");

        HttpEntity<SpotifyPlaylistRequest> httpEntity =
                new HttpEntity<>(playlistRequest, headers);

        RestTemplate restTemplate = new RestTemplate();
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new MappingJackson2HttpMessageConverter());

        restTemplate.setMessageConverters(converters);

        ResponseEntity<SpotifyPlaylistDTO> playlistResponse = restTemplate.postForEntity(SpotifyConstants.URL_CREATE_PLAYLIST, httpEntity, SpotifyPlaylistDTO.class);

        String playlistId = Objects.requireNonNull(playlistResponse.getBody()).getId();

        user.setPlaylistId(playlistId);

        this.userRepository.save(user);

        if(playlistResponse.getStatusCodeValue() == 200 || playlistResponse.getStatusCodeValue() == 201) {
            return this.replaceTrackPlaylist(tracks, playlistId, userToken);
        } else {
            return new ResponseEntity<>(playlistResponse.getStatusCode());
        }
    }

    // method to refresh access token if needed
    public void refreshAccessToken(Token userToken) {
        HttpHeaders httpHeaders = this.getHttpHeadersAuth();

        MultiValueMap<String, String> parameterMap = new LinkedMultiValueMap<>();
        parameterMap.add("grant_type", "refresh_token");
        parameterMap.add("refresh_token", userToken.getRefresh_token());

        HttpEntity<MultiValueMap<String, String>> httpEntity =
                new HttpEntity<>(parameterMap, httpHeaders);

        ResponseEntity<TokenDTO> refreshedToken =
                this.getTokenDTOAuthAndRefresh(SpotifyConstants.URL_REFRESH_TOKEN, httpEntity);

        if(refreshedToken.hasBody()) {

            userToken.setAccess_token(Objects.requireNonNull(refreshedToken.getBody()).getAccess_token());
            userToken.setScope(refreshedToken.getBody().getScope());
            userToken.setExpires_in(refreshedToken.getBody().getExpires_in());
            userToken.setToken_type(refreshedToken.getBody().getToken_type());
            userToken.setLastUpdateTime(Timestamp.from(Instant.now()));

            this.tokenRepository.save(userToken);

        } else {
            throw new InternalServerErrorException("response body is empty");
        }
    }

    /**
     * Method to get recommended tracks
     * @return ResponseEntity<SpotifyTrackDTO[]>
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<SpotifyTrackDTO[]> getSuggestedPlaylist() {
        float acousticness = 0.0f;
        float danceability = 0.0f;
        float energy = 0.0f;
        float instrumentalness = 0.0f;
        float liveness = 0.0f;
        float speechiness = 0.0f;
        float valence = 0.0f;
        int popularity = 0; // --> 0-100 value

        // get average of each audio feature

        ResponseEntity<SpotifyTrackDTO[]> tracksResponse =
                this.getRecentlyPlayed();

        if(!tracksResponse.hasBody()) {
            throw new InternalServerErrorException("Can't get recently played tracks");
        }

        int cantTracks = Objects.requireNonNull(tracksResponse.getBody()).length;

        for (SpotifyTrackDTO track: tracksResponse.getBody()) {
            acousticness = acousticness + track.getAudio_feature().getAcousticness();
            danceability = danceability + track.getAudio_feature().getDanceability();
            energy = energy + track.getAudio_feature().getEnergy();
            instrumentalness = instrumentalness + track.getAudio_feature().getInstrumentalness();
            liveness = liveness + track.getAudio_feature().getLiveness();
            speechiness = speechiness + track.getAudio_feature().getSpeechiness();
            valence = valence + track.getAudio_feature().getValence();
            popularity = popularity + track.getPopularity();
        }

        acousticness = acousticness / cantTracks;
        danceability = danceability / cantTracks;
        energy = energy / cantTracks;
        instrumentalness = instrumentalness / cantTracks;
        liveness = liveness / cantTracks;
        speechiness = speechiness / cantTracks;
        valence = valence / cantTracks;
        popularity = popularity / cantTracks;

        // get 5 random number between 0 and 49 to get the tracks in those indexs, and get the ids
        Random random = new Random();

        String seedTracks;
        Set<String> seedsTracks = new HashSet<>();

        if(tracksResponse.getBody().length < 10) {
            seedTracks = tracksResponse.getBody()[0].getId();
        } else {
            do {
                seedsTracks.add(tracksResponse.getBody()[random.nextInt(tracksResponse.getBody().length)].getId());
            } while(seedsTracks.size() < 5);

            seedTracks = String.join(",", seedsTracks);
        }

        Token userToken = this.getCurrentUserToken();

        HttpHeaders httpHeaders = this.getHttpHeaders(userToken);

        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(SpotifyConstants.URL_RECOMMENDATIONS)
                .queryParam("limit", 50)
                .queryParam("seed_tracks", seedTracks)
                .queryParam("target_acousticness", acousticness)
                .queryParam("target_danceability", danceability)
                .queryParam("target_energy", energy)
                .queryParam("target_instrumentalness", instrumentalness)
                .queryParam("target_liveness", liveness)
                .queryParam("target_speechiness", speechiness)
                .queryParam("target_valence", valence)
                .queryParam("min_popularity", popularity);

        HttpEntity<MultiValueMap<String, String>> httpEntity =
                new HttpEntity<>(httpHeaders);

        // array of tracks simplified
        ResponseEntity<SpotifyTrackArrayDTO> arrayTracksSimplified =
                (ResponseEntity<SpotifyTrackArrayDTO>) this.getClientResponseEntity(this.getRequests(urlBuilder.toUriString(), SpotifyTrackArrayDTO.class, httpEntity));

        List<SpotifyTrackDTO> listTracksSimplified = Arrays.stream(Objects.requireNonNull(arrayTracksSimplified.getBody()).getTracks())
                .collect(Collectors.toList());

        // removing repeated tracks
        Arrays.stream(tracksResponse.getBody()).forEach(spotifyTrackDTO -> listTracksSimplified.removeIf(track -> track.getId().equals(spotifyTrackDTO.getId())));

        String ids = Arrays.stream(listTracksSimplified.toArray(SpotifyTrackDTO[]::new))
                .map(SpotifyTrackDTO::getId)
                .collect(Collectors.joining(","));

        // array of full object tracks
        urlBuilder = UriComponentsBuilder.fromHttpUrl(SpotifyConstants.URL_TRACKS)
                .queryParam("ids", ids);

        ResponseEntity<SpotifyTrackArrayDTO> responseTracks =
                (ResponseEntity<SpotifyTrackArrayDTO>) this.getClientResponseEntity(this.getRequests(urlBuilder.toUriString(), SpotifyTrackArrayDTO.class, httpEntity));

        if(!responseTracks.hasBody()) {
            throw new InternalServerErrorException("There was a problem getting the full object for each tracks");
        }

        return new ResponseEntity<>(Arrays.stream(Objects.requireNonNull(responseTracks.getBody()).getTracks()).toArray(SpotifyTrackDTO[]::new), HttpStatus.OK) ;

    }

    /**
     * Get the recently played tracks by the user
     * @return response
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<SpotifyTrackDTO[]> getRecentlyPlayed() {
        Token userToken = this.getCurrentUserToken();

        HttpHeaders httpHeaders = this.getHttpHeaders(userToken);

        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(SpotifyConstants.URL_RECENTLY_PLAYED)
                .queryParam("limit", 50); // query parameter to get the last 50 played tracks

        HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(httpHeaders);

        // first, we get the last 50 played tracks -> tracks objects are simplified
        ResponseEntity<SpotifyItemsDTO> responsePlayHistory =
                (ResponseEntity<SpotifyItemsDTO>) this.getClientResponseEntity(this.getRequests(urlBuilder.toUriString(), SpotifyItemsDTO.class, httpEntity));

        if(!responsePlayHistory.hasBody()) {
            throw new InternalServerErrorException("There are not Recently played Tracks for this user");
        }

        // Second, we get the full objects for each track recently played

        // --> getting the ids from responsePlayHistory for each track and save them in a String variable
        String ids = Arrays.stream(Objects.requireNonNull(responsePlayHistory.getBody()).getItems()).map(historyObject -> historyObject.getTrack().getId()).collect(Collectors.joining(",")); // separating each id with a ,

        // Third, we get the full track object for each id in the ids variable

        urlBuilder = UriComponentsBuilder.fromHttpUrl(SpotifyConstants.URL_TRACKS)
                .queryParam("ids", ids);

        if(ids.length() == 0) {
            throw new InternalServerErrorException("User doesnt has recently played track");
        }

        ResponseEntity<SpotifyTrackArrayDTO> responseTracks =
                (ResponseEntity<SpotifyTrackArrayDTO>) this.getClientResponseEntity(this.getRequests(urlBuilder.toUriString(), SpotifyTrackArrayDTO.class, httpEntity));

        if(!responseTracks.hasBody()) {
            throw new InternalServerErrorException("There was a problem getting the full object for each tracks");
        }

        urlBuilder = UriComponentsBuilder.fromHttpUrl(SpotifyConstants.URL_FEATURES_TRACKS)
                .queryParam("ids", ids);

        // Forth, we get the features of each track by id
        ResponseEntity<SpotifyAudioFeatureArrayDTO> responseTracksFeatures =
                (ResponseEntity<SpotifyAudioFeatureArrayDTO>) this.getClientResponseEntity(this.getRequests(urlBuilder.toUriString(), SpotifyAudioFeatureArrayDTO.class, httpEntity));

        SpotifyTrackDTO[] tracks = Arrays.stream(Objects.requireNonNull(responseTracks.getBody()).getTracks()).peek(track -> {
            // merge the track with its features

            SpotifyAudioFeaturesDTO audioFeatures = Arrays.stream(Objects.requireNonNull(responseTracksFeatures.getBody()).getAudio_features())
                    .filter(trackFeature -> trackFeature.getId().equals(track.getId())).collect(Collectors.toList()).get(0);

            track.setAudio_feature(audioFeatures);

        }).toArray(SpotifyTrackDTO[]::new);

        return new ResponseEntity<>(tracks, HttpStatus.OK) ;
    }

    /**
     * Get Spotify current profile
     * @return SpotifyUserDTO
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<SpotifyUserDTO> getCurrentUser() {
        Token userToken = this.getCurrentUserToken();

        HttpHeaders httpHeaders = this.getHttpHeaders(userToken);

        HttpEntity<Void> httpEntity = new HttpEntity<>(httpHeaders);

        return (ResponseEntity<SpotifyUserDTO>) this.getClientResponseEntity(this.getRequests(SpotifyConstants.URL_CURRENT_USER, SpotifyUserDTO.class, httpEntity));
    }

    /**
     * Method to create a Client(Front-End) ResponseEntity based on the SpotifyReponseEntity.
     * this is because if we return the ResponseEntity from Spotify our Nginx server will reject the response!!
     * @param responseEntity response
     * @return the new ResponseEntity for the Front-End
     */
    protected ResponseEntity<?> getClientResponseEntity(ResponseEntity<?> responseEntity) {
        return new ResponseEntity<>(responseEntity.getBody(), HttpStatus.OK);
    }

    /**
     * Method to manage all get requests
     * @param urlEndPoint endpoint
     * @param httpEntity httpEntity
     * @param object object type
     * @return Http Response
     */
    protected ResponseEntity<?> getRequests(String urlEndPoint, Class<?> object, HttpEntity<?> httpEntity) {

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setMessageConverters(this.getMessageConverters());
        // the getForEntity dont let set httpEntity wich can have headers
        // this is why we use restTemplate.exchange
        return restTemplate.exchange(urlEndPoint, HttpMethod.GET, httpEntity, object);
    }

    /**
     * Method to get user spotify tokens or refresh access token
     * @return ResponseEntity<TokenDTO> tokens or token info
     */
    protected ResponseEntity<TokenDTO> getTokenDTOAuthAndRefresh(String urlEndPoint,
                                                               HttpEntity<MultiValueMap<String, String>> parametersHttpEntity) {

        RestTemplate restTemplate = new RestTemplate();

        restTemplate.setMessageConverters(this.getMessageConverters());

        return restTemplate.postForEntity(urlEndPoint, parametersHttpEntity, TokenDTO.class);

    }

    /**
     * Check if token is valid; if not try refresh token
     * @param userToken user Token entity info
     */
    protected boolean isTokenExpired(Token userToken) {
        Timestamp checkTime =
                Timestamp.from(userToken.getLastUpdateTime().toInstant().plusSeconds(userToken.getExpires_in()));

        return checkTime.before(Timestamp.from(Instant.now()));
    }

    /**
     * Get HttpHeaders for general purpose, like get current profile or other requests
     * @param userToken userToken tokens details entity
     * @return HttpHeaders
     */
    protected HttpHeaders getHttpHeaders(Token userToken) {

        if(this.isTokenExpired(userToken)) {
            this.refreshAccessToken(userToken); // if needed this will refresh the access token
        }

        String value = userToken.getToken_type() + " " + userToken.getAccess_token();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        httpHeaders.add("Authorization", value);

        return httpHeaders;
    }

    /**
     * Method to get Current user Token Entity
     * @return Token Entity
     */
    private Token getCurrentUserToken() {

        Optional<User> currentUser = this.userService.getCurrentUser();

        if(currentUser.isEmpty()) {
            throw new UserNotFoundException();
        }

        if(currentUser.get().getToken() == null) {
            throw new InternalServerErrorException("User doesn't have Access Token!");
        }

        return currentUser.get().getToken();
    }

    /**
     * Get HttpHeaders for Authorization flow or refresh access token
     * @return HttpHeaders for Authorization flow or refresh access token
     */
    public HttpHeaders getHttpHeadersAuth() {
        String stringToBeEncoded =
                applicationProperties.getSpotify().getClientId() + ":" + applicationProperties.getSpotify().getClientSecret();

        String value = "Basic " + Base64.getEncoder().encodeToString(stringToBeEncoded.getBytes());

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        httpHeaders.add("Authorization", value);

        return httpHeaders;
    }

    /**
     * Method to get MessageConverter(s) for JSON and x-www-urlencoded
     * @return List of Http Message converters
     */
    private List<HttpMessageConverter<?>> getMessageConverters() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new FormHttpMessageConverter()); // Message converter for application/x-www-urlencoded -> Request
        converters.add(new MappingJackson2HttpMessageConverter()); // Message converter for application/JSON -> Response

        return converters;
    }
}
