package com.unciv.logic

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.BFS
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.metadata.GameParameters
import java.util.*
import kotlin.collections.ArrayList

class GameStarter{
    fun startNewGame(newGameParameters: GameParameters): GameInfo {
        val gameInfo = GameInfo()

        gameInfo.gameParameters = newGameParameters
        gameInfo.tileMap = TileMap(newGameParameters)
        gameInfo.tileMap.gameInfo = gameInfo // need to set this transient before placing units in the map
        gameInfo.difficulty = newGameParameters.difficulty


        val availableCivNames = Stack<String>()
        availableCivNames.addAll(GameBasics.Nations.filter { !it.value.isCityState() }.keys.shuffled())
        availableCivNames.removeAll(newGameParameters.players.map { it.chosenCiv })
        availableCivNames.remove("Barbarians")


        val barbarianCivilization = CivilizationInfo("Barbarians")
        gameInfo.civilizations.add(barbarianCivilization)

        for(player in newGameParameters.players.sortedBy { it.chosenCiv=="Random" }) {
            val nationName = if(player.chosenCiv!="Random") player.chosenCiv
            else availableCivNames.pop()

            val playerCiv = CivilizationInfo(nationName)
            playerCiv.playerType = player.playerType
            playerCiv.playerId = player.playerId
            gameInfo.civilizations.add(playerCiv)
        }


        val availableCityStatesNames = Stack<String>()
        availableCityStatesNames.addAll(GameBasics.Nations.filter { it.value.isCityState() }.keys.shuffled())

        for (cityStateName in availableCityStatesNames.take(newGameParameters.numberOfCityStates)) {
            val civ = CivilizationInfo(cityStateName)
            gameInfo.civilizations.add(civ)
        }

        gameInfo.setTransients() // needs to be before placeBarbarianUnit because it depends on the tilemap having its gameinfo set

        for (civInfo in gameInfo.civilizations.filter {!it.isBarbarian() && !it.isPlayerCivilization()}) {
            for (tech in gameInfo.getDifficulty().aiFreeTechs)
                civInfo.tech.addTechnology(tech)
        }

        // and only now do we add units for everyone, because otherwise both the gameInfo.setTransients() and the placeUnit will both add the unit to the civ's unit list!


        val startingLocations = getStartingLocations(
                gameInfo.civilizations.filter { !it.isBarbarian() },
                gameInfo.tileMap)

        for (civ in gameInfo.civilizations.filter { !it.isBarbarian() }) {
            val startingLocation = startingLocations[civ]!!

            civ.placeUnitNearTile(startingLocation.position, Constants.settler)
            civ.placeUnitNearTile(startingLocation.position, "Warrior")
            civ.placeUnitNearTile(startingLocation.position, "Scout")
        }

        return gameInfo
    }

    fun getStartingLocations(civs:List<CivilizationInfo>,tileMap: TileMap): HashMap<CivilizationInfo, TileInfo> {
        var landTiles = tileMap.values
                .filter { it.isLand && !it.getBaseTerrain().impassable }

        val landTilesInBigEnoughGroup = ArrayList<TileInfo>()
        while(landTiles.any()){
            val bfs = BFS(landTiles.random()){it.isLand && !it.getBaseTerrain().impassable}
            bfs.stepToEnd()
            val tilesInGroup = bfs.tilesReached.keys
            landTiles = landTiles.filter { it !in tilesInGroup }
            if(tilesInGroup.size > 20) // is this a good number? I dunno, but it's easy enough to change later on
                landTilesInBigEnoughGroup.addAll(tilesInGroup)
        }

        for(minimumDistanceBetweenStartingLocations in tileMap.tileMatrix.size/3 downTo 0){
            val freeTiles = landTilesInBigEnoughGroup
                    .filter {  vectorIsAtLeastNTilesAwayFromEdge(it.position,minimumDistanceBetweenStartingLocations,tileMap)}
                    .toMutableList()

            val startingLocations = HashMap<CivilizationInfo,TileInfo>()

            val tilesWithStartingLocations = tileMap.values
                    .filter { it.improvement!=null && it.improvement!!.startsWith("StartingLocation ") }

            val civsOrderedByAvailableLocations = civs.sortedBy {civ ->
                when {
                    tilesWithStartingLocations.any { it.improvement=="StartingLocation "+civ.civName } -> 1 // harshest requirements
                    civ.nation.startBias.isNotEmpty() -> 2 // less harsh
                    else -> 3
                }  // no requirements
            }

            for(civ in civsOrderedByAvailableLocations){
                var startingLocation:TileInfo
                val presetStartingLocation = tilesWithStartingLocations.firstOrNull { it.improvement=="StartingLocation "+civ.civName }
                if(presetStartingLocation!=null) startingLocation = presetStartingLocation
                else {
                    if (freeTiles.isEmpty()) break // we failed to get all the starting locations with this minimum distance
                    var preferredTiles = freeTiles.toList()

                    for (startBias in civ.nation.startBias) {
                        if (startBias.startsWith("Avoid ")) {
                            val tileToAvoid = startBias.removePrefix("Avoid ")
                            preferredTiles = preferredTiles.filter { it.baseTerrain != tileToAvoid && it.terrainFeature != tileToAvoid }
                        } else if (startBias == Constants.coast) preferredTiles = preferredTiles.filter { it.neighbors.any { n -> n.baseTerrain == startBias } }
                        else preferredTiles = preferredTiles.filter { it.baseTerrain == startBias || it.terrainFeature == startBias }
                    }

                    startingLocation = if (preferredTiles.isNotEmpty()) preferredTiles.random() else freeTiles.random()
                }
                startingLocations[civ] = startingLocation
                freeTiles.removeAll(tileMap.getTilesInDistance(startingLocation.position,minimumDistanceBetweenStartingLocations))
            }
            if(startingLocations.size < civs.size) continue // let's try again with less minimum distance!

            for(tile in tilesWithStartingLocations) tile.improvement=null // get rid of the starting location improvements
            return startingLocations
        }
        throw Exception("Didn't manage to get starting locations even with distance of 1?")
    }

    fun vectorIsAtLeastNTilesAwayFromEdge(vector: Vector2, n:Int, tileMap: TileMap): Boolean {
        // Since all maps are HEXAGONAL, the easiest way of checking if a tile is n steps away from the
        // edge is checking the distance to the CENTER POINT
        // Can't believe we used a dumb way of calculating this before!
        val hexagonalRadius = -tileMap.leftX
        val distanceFromCenter = HexMath().getDistance(vector, Vector2.Zero)
        return hexagonalRadius-distanceFromCenter >= n
    }

}