/*
 * Copyright (C) 2004-2015 L2J Server
 * 
 * This file is part of L2J Server.
 * 
 * L2J Server is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * L2J Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package l2r.gameserver.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import l2r.Config;
import l2r.gameserver.GameTimeController;
import l2r.gameserver.SevenSignsFestival;
import l2r.gameserver.ThreadPoolManager;
import l2r.gameserver.data.xml.impl.ItemData;
import l2r.gameserver.enums.MessageType;
import l2r.gameserver.enums.PartyDistributionType;
import l2r.gameserver.enums.ZoneIdType;
import l2r.gameserver.instancemanager.PcCafePointsManager;
import l2r.gameserver.model.actor.L2Attackable;
import l2r.gameserver.model.actor.L2Character;
import l2r.gameserver.model.actor.L2Summon;
import l2r.gameserver.model.actor.instance.L2PcInstance;
import l2r.gameserver.model.actor.instance.L2ServitorInstance;
import l2r.gameserver.model.entity.DimensionalRift;
import l2r.gameserver.model.holders.ItemHolder;
import l2r.gameserver.model.itemcontainer.Inventory;
import l2r.gameserver.model.items.instance.L2ItemInstance;
import l2r.gameserver.model.stats.Stats;
import l2r.gameserver.network.SystemMessageId;
import l2r.gameserver.network.serverpackets.ExAskModifyPartyLooting;
import l2r.gameserver.network.serverpackets.ExCloseMPCC;
import l2r.gameserver.network.serverpackets.ExOpenMPCC;
import l2r.gameserver.network.serverpackets.ExPartyPetWindowAdd;
import l2r.gameserver.network.serverpackets.ExPartyPetWindowDelete;
import l2r.gameserver.network.serverpackets.ExSetPartyLooting;
import l2r.gameserver.network.serverpackets.L2GameServerPacket;
import l2r.gameserver.network.serverpackets.PartyMemberPosition;
import l2r.gameserver.network.serverpackets.PartySmallWindowAdd;
import l2r.gameserver.network.serverpackets.PartySmallWindowAll;
import l2r.gameserver.network.serverpackets.PartySmallWindowDelete;
import l2r.gameserver.network.serverpackets.PartySmallWindowDeleteAll;
import l2r.gameserver.network.serverpackets.SystemMessage;
import l2r.gameserver.util.Util;
import l2r.util.Rnd;

import gr.sr.configsEngine.configs.impl.CustomServerConfigs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class serves as a container for player parties.
 * @author nuocnam
 */
public class L2Party extends AbstractPlayerGroup
{
	private static final Logger _log = LoggerFactory.getLogger(L2Party.class);
	
	// @formatter:off
	private static final double[] BONUS_EXP_SP =
	{
		1.0, 1.10, 1.20, 1.30, 1.40, 1.50, 2.0, 2.10, 2.20
	};
	// @formatter:on
	
	private static final Duration PARTY_POSITION_BROADCAST_INTERVAL = Duration.ofSeconds(12);
	private static final Duration PARTY_DISTRIBUTION_TYPE_REQUEST_TIMEOUT = Duration.ofSeconds(15);
	
	public static final byte ITEM_LOOTER = 0;
	public static final byte ITEM_RANDOM = 1;
	public static final byte ITEM_RANDOM_SPOIL = 2;
	public static final byte ITEM_ORDER = 3;
	public static final byte ITEM_ORDER_SPOIL = 4;
	
	private final List<L2PcInstance> _members;
	private boolean _pendingInvitation = false;
	private long _pendingInviteTimeout;
	private int _partyLvl = 0;
	private volatile PartyDistributionType _distributionType = PartyDistributionType.FINDERS_KEEPERS;
	private volatile PartyDistributionType _changeRequestDistributionType;
	private volatile Future<?> _changeDistributionTypeRequestTask = null;
	private volatile Set<Integer> _changeDistributionTypeAnswers = null;
	private int _itemLastLoot = 0;
	private L2CommandChannel _commandChannel = null;
	private DimensionalRift _dr;
	private Future<?> _positionBroadcastTask = null;
	protected PartyMemberPosition _positionPacket;
	private boolean _disbanding = false;
	
	/**
	 * Construct a new L2Party object with a single member - the leader.
	 * @param leader the leader of this party
	 * @param partyDistributionType the item distribution rule of this party
	 */
	public L2Party(L2PcInstance leader, PartyDistributionType partyDistributionType)
	{
		_members = new CopyOnWriteArrayList<>();
		_members.add(leader);
		_partyLvl = leader.getLevel();
		_distributionType = partyDistributionType;
	}
	
	// L2GamePlay: used only for event engine
	public L2Party(L2PcInstance leader)
	{
		_members = new CopyOnWriteArrayList<>();
		_members.add(leader);
		_partyLvl = leader.getLevel();
		_distributionType = PartyDistributionType.RANDOM;
	}
	
	/**
	 * Check if another player can start invitation process.
	 * @return {@code true} if this party waits for a response on an invitation, {@code false} otherwise
	 */
	public boolean getPendingInvitation()
	{
		return _pendingInvitation;
	}
	
	/**
	 * Set invitation process flag and store time for expiration. <br>
	 * Happens when a player joins party or declines to join.
	 * @param val the pending invitation state to set
	 */
	public void setPendingInvitation(boolean val)
	{
		_pendingInvitation = val;
		_pendingInviteTimeout = GameTimeController.getInstance().getGameTicks() + (L2PcInstance.REQUEST_TIMEOUT * GameTimeController.TICKS_PER_SECOND);
	}
	
	/**
	 * Check if a player invitation request is expired.
	 * @return {@code true} if time is expired, {@code false} otherwise
	 * @see l2r.gameserver.model.actor.instance.L2PcInstance#isRequestExpired()
	 */
	public boolean isInvitationRequestExpired()
	{
		return (_pendingInviteTimeout <= GameTimeController.getInstance().getGameTicks());
	}
	
	/**
	 * Get a random member from this party.
	 * @param itemId the ID of the item for which the member must have inventory space
	 * @param target the object of which the member must be within a certain range (must not be null)
	 * @return a random member from this party or {@code null} if none of the members have inventory space for the specified item
	 */
	private L2PcInstance getCheckedRandomMember(int itemId, L2Character target)
	{
		List<L2PcInstance> availableMembers = new ArrayList<>();
		for (L2PcInstance member : getMembers())
		{
			if (member.getInventory().validateCapacityByItemId(itemId) && Util.checkIfInRange(Config.ALT_PARTY_RANGE2, target, member, true))
			{
				availableMembers.add(member);
			}
		}
		if (!availableMembers.isEmpty())
		{
			return availableMembers.get(Rnd.get(availableMembers.size()));
		}
		return null;
	}
	
	/**
	 * get next item looter
	 * @param ItemId
	 * @param target
	 * @return
	 */
	private L2PcInstance getCheckedNextLooter(int ItemId, L2Character target)
	{
		for (int i = 0; i < getMemberCount(); i++)
		{
			if (++_itemLastLoot >= getMemberCount())
			{
				_itemLastLoot = 0;
			}
			L2PcInstance member;
			try
			{
				member = getMembers().get(_itemLastLoot);
				if (member.getInventory().validateCapacityByItemId(ItemId) && Util.checkIfInRange(Config.ALT_PARTY_RANGE2, target, member, true))
				{
					return member;
				}
			}
			catch (Exception e)
			{
				// continue, take another member if this just logged off
			}
		}
		
		return null;
	}
	
	/**
	 * get next item looter
	 * @param player
	 * @param ItemId
	 * @param spoil
	 * @param target
	 * @return
	 */
	private L2PcInstance getActualLooter(L2PcInstance player, int ItemId, boolean spoil, L2Character target)
	{
		L2PcInstance looter = player;
		
		switch (_distributionType)
		{
			case RANDOM:
				if (!spoil)
				{
					looter = getCheckedRandomMember(ItemId, target);
				}
				break;
			case RANDOM_INCLUDING_SPOIL:
				looter = getCheckedRandomMember(ItemId, target);
				break;
			case BY_TURN:
				if (!spoil)
				{
					looter = getCheckedNextLooter(ItemId, target);
				}
				break;
			case BY_TURN_INCLUDING_SPOIL:
				looter = getCheckedNextLooter(ItemId, target);
				break;
		}
		
		return looter != null ? looter : player;
	}
	
	/**
	 * Broadcasts UI update and User Info for new party leader.
	 */
	public void broadcastToPartyMembersNewLeader()
	{
		for (L2PcInstance member : getMembers())
		{
			if (member != null)
			{
				member.sendPacket(PartySmallWindowDeleteAll.STATIC_PACKET);
				member.sendPacket(new PartySmallWindowAll(member, this));
				member.broadcastUserInfo();
			}
		}
	}
	
	/**
	 * Broadcasts packet to every party member
	 * @param msg
	 */
	public void broadcastToPartyMembers(L2GameServerPacket msg)
	{
		for (L2PcInstance member : getMembers())
		{
			if (member != null)
			{
				member.sendPacket(msg);
			}
		}
	}
	
	/**
	 * Send a Server->Client packet to all other L2PcInstance of the Party.<BR>
	 * <BR>
	 * @param player
	 * @param msg
	 */
	public void broadcastToPartyMembers(L2PcInstance player, L2GameServerPacket msg)
	{
		for (L2PcInstance member : getMembers())
		{
			if ((member != null) && (member.getObjectId() != player.getObjectId()))
			{
				member.sendPacket(msg);
			}
		}
	}
	
	/**
	 * adds new member to party
	 * @param player
	 */
	public void addPartyMember(L2PcInstance player)
	{
		if (getMembers().contains(player))
		{
			return;
		}
		
		if (_changeRequestDistributionType != null)
		{
			finishLootRequest(false); // cancel on invite
		}
		// sends new member party window for all members
		// we do all actions before adding member to a list, this speeds things up a little
		player.sendPacket(new PartySmallWindowAll(player, this));
		
		// sends pets/summons of party members
		for (L2PcInstance pMember : getMembers())
		{
			if ((pMember != null) && pMember.hasSummon())
			{
				player.sendPacket(new ExPartyPetWindowAdd(pMember.getSummon()));
			}
		}
		
		SystemMessage msg = SystemMessage.getSystemMessage(SystemMessageId.YOU_JOINED_S1_PARTY);
		msg.addString(getLeader().getName());
		player.sendPacket(msg);
		
		msg = SystemMessage.getSystemMessage(SystemMessageId.C1_JOINED_PARTY);
		msg.addString(player.getName());
		broadcastToPartyMembers(msg);
		
		broadcastToPartyMembers(new PartySmallWindowAdd(player, this));
		// send the position of all party members to the new party member
		// player.sendPacket(new PartyMemberPosition(this));
		// send the position of the new party member to all party members (except the new one - he knows his own position)
		// broadcastToPartyMembers(player, new PartyMemberPosition(this));
		
		// if member has pet/summon add it to other as well
		if (player.hasSummon())
		{
			broadcastPacket(new ExPartyPetWindowAdd(player.getSummon()));
		}
		
		// add player to party, adjust party level
		getMembers().add(player);
		
		if (isInCommandChannel())
		{
			if (player.getLevel() > getCommandChannel().getLevel())
			{
				getCommandChannel().setLevel(player.getLevel());
			}
		}
		else if (player.getLevel() > _partyLvl)
		{
			_partyLvl = player.getLevel();
		}
		
		// update partySpelled
		L2Summon summon;
		for (L2PcInstance member : getMembers())
		{
			if (member != null)
			{
				member.updateEffectIcons(true); // update party icons only
				summon = member.getSummon();
				member.broadcastUserInfo();
				if (summon != null)
				{
					summon.updateEffectIcons();
				}
			}
		}
		
		if (isInDimensionalRift())
		{
			_dr.partyMemberInvited();
		}
		
		// open the CCInformationwindow
		if (isInCommandChannel())
		{
			player.sendPacket(ExOpenMPCC.STATIC_PACKET);
		}
		
		if (_positionBroadcastTask == null)
		{
			_positionBroadcastTask = ThreadPoolManager.getInstance().scheduleGeneralAtFixedRate(() ->
			{
				if (_positionPacket == null)
				{
					_positionPacket = new PartyMemberPosition(this);
				}
				else
				{
					_positionPacket.reuse(this);
				}
				broadcastPacket(_positionPacket);
			} , PARTY_POSITION_BROADCAST_INTERVAL.toMillis() / 2, PARTY_POSITION_BROADCAST_INTERVAL.toMillis());
		}
		
		// L2GamePlay: Distribution mod
		if ((getLeader().hasEvenlyDistributedLoot() || CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_FORCED) && CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS)
		{
			player.sendMessage("This party has evenly distribution for party loot.");
			
			if (CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_SEND_LIST && (CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_CACHED_HTML != null))
			{
				player.sendPacket(CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_CACHED_HTML);
			}
		}
	}
	
	/**
	 * Removes a party member using its name.
	 * @param name player the player to be removed from the party.
	 * @param type the message type {@link MessageType}.
	 */
	public void removePartyMember(String name, MessageType type)
	{
		removePartyMember(getPlayerByName(name), type);
	}
	
	/**
	 * Removes a party member instance.
	 * @param player the player to be removed from the party.
	 * @param type the message type {@link MessageType}.
	 */
	public void removePartyMember(L2PcInstance player, MessageType type)
	{
		if (getMembers().contains(player))
		{
			final boolean isLeader = isLeader(player);
			if (!_disbanding)
			{
				if ((getMembers().size() == 2) || (isLeader && !Config.ALT_LEAVE_PARTY_LEADER && (type != MessageType.Disconnected)))
				{
					disbandParty();
					return;
				}
			}
			
			getMembers().remove(player);
			recalculatePartyLevel();
			
			if (player.isFestivalParticipant())
			{
				SevenSignsFestival.getInstance().updateParticipants(player, this);
			}
			
			try
			{
				if (player.getFusionSkill() != null)
				{
					player.abortCast();
				}
				
				for (L2Character character : player.getKnownList().getKnownCharacters())
				{
					if ((character.getFusionSkill() != null) && (character.getFusionSkill().getTarget() == player))
					{
						character.abortCast();
					}
				}
			}
			catch (Exception e)
			{
				_log.warn(String.valueOf(e));
			}
			
			SystemMessage msg;
			if (type == MessageType.Expelled)
			{
				player.sendPacket(SystemMessageId.HAVE_BEEN_EXPELLED_FROM_PARTY);
				msg = SystemMessage.getSystemMessage(SystemMessageId.C1_WAS_EXPELLED_FROM_PARTY);
				msg.addString(player.getName());
				broadcastPacket(msg);
			}
			else if ((type == MessageType.Left) || (type == MessageType.Disconnected))
			{
				player.sendPacket(SystemMessageId.YOU_LEFT_PARTY);
				msg = SystemMessage.getSystemMessage(SystemMessageId.C1_LEFT_PARTY);
				msg.addString(player.getName());
				broadcastPacket(msg);
			}
			
			// UI update.
			player.sendPacket(PartySmallWindowDeleteAll.STATIC_PACKET);
			player.setParty(null);
			broadcastPacket(new PartySmallWindowDelete(player));
			if (player.hasSummon())
			{
				broadcastPacket(new ExPartyPetWindowDelete(player.getSummon()));
			}
			
			if (isInDimensionalRift())
			{
				_dr.partyMemberExited(player);
			}
			
			// Close the CCInfoWindow
			if (isInCommandChannel())
			{
				player.sendPacket(new ExCloseMPCC());
			}
			if (isLeader && (getMembers().size() > 1) && (Config.ALT_LEAVE_PARTY_LEADER || (type == MessageType.Disconnected)))
			{
				msg = SystemMessage.getSystemMessage(SystemMessageId.C1_HAS_BECOME_A_PARTY_LEADER);
				msg.addString(getLeader().getName());
				broadcastPacket(msg);
				broadcastToPartyMembersNewLeader();
			}
			else if (getMembers().size() == 1)
			{
				if (isInCommandChannel())
				{
					// delete the whole command channel when the party who opened the channel is disbanded
					if (getCommandChannel().getLeader().getObjectId() == getLeader().getObjectId())
					{
						getCommandChannel().disbandChannel();
					}
					else
					{
						getCommandChannel().removeParty(this);
					}
				}
				
				if (getLeader() != null)
				{
					getLeader().setParty(null);
				}
				
				if (_changeDistributionTypeRequestTask != null)
				{
					_changeDistributionTypeRequestTask.cancel(true);
					_changeDistributionTypeRequestTask = null;
				}
				
				if (_positionBroadcastTask != null)
				{
					_positionBroadcastTask.cancel(false);
					_positionBroadcastTask = null;
				}
				_members.clear();
			}
		}
	}
	
	/**
	 * Disperse a party and send a message to all its members.
	 */
	public void disbandParty()
	{
		_disbanding = true;
		if (_members != null)
		{
			broadcastPacket(SystemMessage.getSystemMessage(SystemMessageId.PARTY_DISPERSED));
			for (L2PcInstance member : _members)
			{
				if (member != null)
				{
					removePartyMember(member, MessageType.None);
				}
			}
		}
	}
	
	/**
	 * Change party leader (used for string arguments)
	 * @param name the name of the player to set as the new party leader
	 */
	public void changePartyLeader(String name)
	{
		setLeader(getPlayerByName(name));
	}
	
	@Override
	public void setLeader(L2PcInstance player)
	{
		if ((player != null) && !player.isInDuel())
		{
			if (getMembers().contains(player))
			{
				if (isLeader(player))
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_TRANSFER_RIGHTS_TO_YOURSELF);
				}
				else
				{
					// Swap party members
					L2PcInstance temp = getLeader();
					int p1 = getMembers().indexOf(player);
					getMembers().set(0, player);
					getMembers().set(p1, temp);
					
					SystemMessage msg = SystemMessage.getSystemMessage(SystemMessageId.C1_HAS_BECOME_A_PARTY_LEADER);
					msg.addString(getLeader().getName());
					broadcastPacket(msg);
					broadcastToPartyMembersNewLeader();
					if (isInCommandChannel() && _commandChannel.isLeader(temp))
					{
						_commandChannel.setLeader(getLeader());
						msg = SystemMessage.getSystemMessage(SystemMessageId.COMMAND_CHANNEL_LEADER_NOW_C1);
						msg.addString(_commandChannel.getLeader().getName());
						_commandChannel.broadcastPacket(msg);
					}
					if (player.isInPartyMatchRoom())
					{
						PartyMatchRoom room = PartyMatchRoomList.getInstance().getPlayerRoom(player);
						room.changeLeader(player);
					}
				}
			}
			else
			{
				player.sendPacket(SystemMessageId.YOU_CAN_TRANSFER_RIGHTS_ONLY_TO_ANOTHER_PARTY_MEMBER);
			}
		}
	}
	
	/**
	 * finds a player in the party by name
	 * @param name
	 * @return
	 */
	private L2PcInstance getPlayerByName(String name)
	{
		for (L2PcInstance member : getMembers())
		{
			if (member.getName().equalsIgnoreCase(name))
			{
				return member;
			}
		}
		return null;
	}
	
	/**
	 * distribute item(s) to party members
	 * @param player
	 * @param item
	 */
	public void distributeItem(L2PcInstance player, L2ItemInstance item)
	{
		distributeItem(player, item, true);
	}
	
	/**
	 * distribute item(s) to party members
	 * @param player
	 * @param item
	 * @param mustMultiply
	 */
	public void distributeItem(L2PcInstance player, L2ItemInstance item, boolean mustMultiply)
	{
		final int itemId = item.getId();
		if (itemId == Inventory.ADENA_ID)
		{
			distributeAdena(player, item.getCount(), player, mustMultiply);
			ItemData.getInstance().destroyItem("Party", item, player, null);
			return;
		}
		
		if (CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS && (CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_FORCED || ((getLeader() != null) && getLeader().hasEvenlyDistributedLoot())) && CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_LIST.contains(itemId))
		{
			evenlyDistribute(player, itemId, item.getCount(), player, mustMultiply);
			ItemData.getInstance().destroyItem("Party", item, player, null);
			return;
		}
		
		L2PcInstance target = getActualLooter(player, item.getId(), false, player);
		target.addItem("Party", item, player, true);
		
		// Send messages to other party members about reward
		if (item.getCount() > 1)
		{
			SystemMessage msg = SystemMessage.getSystemMessage(SystemMessageId.C1_OBTAINED_S3_S2);
			msg.addString(target.getName());
			msg.addItemName(item);
			msg.addLong(item.getCount());
			broadcastToPartyMembers(target, msg);
		}
		else
		{
			SystemMessage msg = SystemMessage.getSystemMessage(SystemMessageId.C1_OBTAINED_S2);
			msg.addString(target.getName());
			msg.addItemName(item);
			broadcastToPartyMembers(target, msg);
		}
	}
	
	/**
	 * Distributes item loot between party members.
	 * @param player the reference player
	 * @param itemId the item ID
	 * @param itemCount the item count
	 * @param spoil {@code true} if it's spoil loot
	 * @param target the NPC target
	 */
	public void distributeItem(L2PcInstance player, int itemId, long itemCount, boolean spoil, L2Attackable target)
	{
		if (itemId == Inventory.ADENA_ID)
		{
			distributeAdena(player, itemCount, target);
			return;
		}
		
		if (CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS && CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_FOR_SPOIL_ENABLED && (CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_FORCED || ((getLeader() != null) && getLeader().hasEvenlyDistributedLoot())) && CustomServerConfigs.EVENLY_DISTRIBUTED_ITEMS_LIST.contains(itemId))
		{
			evenlyDistribute(player, itemId, itemCount, target);
			return;
		}
		
		L2PcInstance looter = getActualLooter(player, itemId, spoil, target);
		if (looter.isPremium())
		{
			itemCount *= looter.calcPremiumDropMultipliers(itemId);
			looter.addItem(spoil ? "Sweeper Party" : "Party", itemId, itemCount, target, true);
		}
		else
		{
			looter.addItem(spoil ? "Sweeper Party" : "Party", itemId, itemCount, target, true);
		}
		
		// Send messages to other party members about reward
		if (itemCount > 1)
		{
			SystemMessage msg = spoil ? SystemMessage.getSystemMessage(SystemMessageId.C1_SWEEPED_UP_S3_S2) : SystemMessage.getSystemMessage(SystemMessageId.C1_OBTAINED_S3_S2);
			msg.addString(looter.getName());
			msg.addItemName(itemId);
			msg.addLong(itemCount);
			broadcastToPartyMembers(looter, msg);
		}
		else
		{
			SystemMessage msg = spoil ? SystemMessage.getSystemMessage(SystemMessageId.C1_SWEEPED_UP_S2) : SystemMessage.getSystemMessage(SystemMessageId.C1_OBTAINED_S2);
			msg.addString(looter.getName());
			msg.addItemName(itemId);
			broadcastToPartyMembers(looter, msg);
		}
	}
	
	/**
	 * Method overload for {@link L2Party#distributeItem(L2PcInstance, int, long, boolean, L2Attackable)}
	 * @param player the reference player
	 * @param item the item holder
	 * @param spoil {@code true} if it's spoil loot
	 * @param target the NPC target
	 */
	public void distributeItem(L2PcInstance player, ItemHolder item, boolean spoil, L2Attackable target)
	{
		distributeItem(player, item.getId(), item.getCount(), spoil, target);
	}
	
	/**
	 * distribute adena to party members
	 * @param player
	 * @param adena
	 * @param target
	 */
	public void distributeAdena(L2PcInstance player, long adena, L2Character target)
	{
		distributeAdena(player, adena, target, true);
	}
	
	/**
	 * distribute adena to party members
	 * @param player
	 * @param adena
	 * @param target
	 * @param mustMultiply
	 */
	public void distributeAdena(L2PcInstance player, long adena, L2Character target, boolean mustMultiply)
	{
		final Map<L2PcInstance, AtomicLong> toReward = new HashMap<>(9);
		
		for (final L2PcInstance member : getMembers())
		{
			if (Util.checkIfInRange(Config.ALT_PARTY_RANGE2, target, member, true))
			{
				toReward.put(member, new AtomicLong());
			}
		}
		
		if (!toReward.isEmpty())
		{
			long leftOver = adena % toReward.size();
			final long count = adena / toReward.size();
			
			if (count > 0)
			{
				for (AtomicLong member : toReward.values())
				{
					member.addAndGet(count);
				}
			}
			
			if (leftOver > 0)
			{
				List<L2PcInstance> keys = new ArrayList<>(toReward.keySet());
				
				while (leftOver-- > 0)
				{
					Collections.shuffle(keys);
					toReward.get(keys.get(0)).incrementAndGet();
				}
			}
			
			for (Entry<L2PcInstance, AtomicLong> member : toReward.entrySet())
			{
				if (member.getValue().get() > 0)
				{
					if (member.getKey().isPremium() && mustMultiply)
					{
						long tempCount = member.getValue().get();
						tempCount *= member.getKey().calcPremiumDropMultipliers(57);
						if (member.getKey().getInventory().getAdenaInstance() != null)
						{
							member.getKey().addAdena("Party", tempCount, player, true);
						}
						else
						{
							member.getKey().addItem("Party", 57, tempCount, player, true);
						}
					}
					else
					{
						if (member.getKey().getInventory().getAdenaInstance() != null)
						{
							member.getKey().addAdena("Party", member.getValue().get(), player, true);
						}
						else
						{
							member.getKey().addItem("Party", 57, member.getValue().get(), player, true);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Distribute items evenly for each party member.
	 * @param player
	 * @param itemCount
	 * @param target
	 * @param itemId
	 */
	public void evenlyDistribute(L2PcInstance player, int itemId, long itemCount, L2Character target)
	{
		evenlyDistribute(player, itemId, itemCount, target, true);
	}
	
	/**
	 * Distribute items evenly for each party member.
	 * @param player
	 * @param itemCount
	 * @param target
	 * @param itemId
	 * @param mustMultiply
	 */
	public void evenlyDistribute(L2PcInstance player, int itemId, long itemCount, L2Character target, boolean mustMultiply)
	{
		final Map<L2PcInstance, AtomicLong> toReward = new HashMap<>(9);
		
		for (final L2PcInstance member : getMembers())
		{
			if (Util.checkIfInRange(Config.ALT_PARTY_RANGE2, target, member, true))
			{
				toReward.put(member, new AtomicLong());
			}
		}
		
		if (!toReward.isEmpty())
		{
			long leftOver = itemCount % toReward.size();
			final long count = itemCount / toReward.size();
			
			if (count > 0)
			{
				for (AtomicLong member : toReward.values())
				{
					member.addAndGet(count);
				}
			}
			
			if (leftOver > 0)
			{
				List<L2PcInstance> keys = new ArrayList<>(toReward.keySet());
				
				while (leftOver-- > 0)
				{
					Collections.shuffle(keys);
					toReward.get(keys.get(0)).incrementAndGet();
				}
			}
			
			for (Entry<L2PcInstance, AtomicLong> member : toReward.entrySet())
			{
				if (member.getValue().get() > 0)
				{
					if (member.getKey().isPremium() && mustMultiply)
					{
						long tempCount = member.getValue().get();
						tempCount *= member.getKey().calcPremiumDropMultipliers(itemId);
						member.getKey().addItem("Party", itemId, tempCount, player, true);
					}
					else
					{
						member.getKey().addItem("Party", itemId, member.getValue().get(), player, true);
					}
				}
			}
		}
	}
	
	/**
	 * Distribute Experience and SP rewards to L2PcInstance Party members in the known area of the last attacker.<BR>
	 * <BR>
	 * <B><U> Actions</U> :</B><BR>
	 * <BR>
	 * <li>Get the L2PcInstance owner of the L2ServitorInstance (if necessary)</li>
	 * <li>Calculate the Experience and SP reward distribution rate</li>
	 * <li>Add Experience and SP to the L2PcInstance</li><BR>
	 * <BR>
	 * <FONT COLOR=#FF0000><B> <U>Caution</U> : This method DOESN'T GIVE rewards to L2PetInstance</B></FONT><BR>
	 * <BR>
	 * Exception are L2PetInstances that leech from the owner's XP; they get the exp indirectly, via the owner's exp gain<BR>
	 * @param xpReward_pr
	 * @param spReward_pr
	 * @param xpReward The Experience reward to distribute
	 * @param spReward The SP reward to distribute
	 * @param rewardedMembers The list of L2PcInstance to reward
	 * @param topLvl
	 * @param partyDmg
	 * @param target
	 */
	public void distributeXpAndSp(long xpReward_pr, int spReward_pr, long xpReward, int spReward, List<L2PcInstance> rewardedMembers, int topLvl, int partyDmg, L2Attackable target)
	{
		final List<L2PcInstance> validMembers = getValidMembers(rewardedMembers, topLvl);
		
		xpReward *= getExpBonus(validMembers.size());
		spReward *= getSpBonus(validMembers.size());
		xpReward_pr *= getExpBonus(validMembers.size());
		spReward_pr *= getSpBonus(validMembers.size());
		
		int sqLevelSum = 0;
		for (L2PcInstance member : validMembers)
		{
			sqLevelSum += (member.getLevel() * member.getLevel());
		}
		
		final float vitalityPoints = (target.getVitalityPoints(partyDmg) * Config.RATE_PARTY_XP) / validMembers.size();
		final boolean useVitalityRate = target.useVitalityRate();
		
		for (L2PcInstance member : rewardedMembers)
		{
			if (member.isDead())
			{
				continue;
			}
			
			long addexp;
			int addsp;
			
			if (validMembers.contains(member))
			{
				final float penalty = member.hasServitor() ? ((L2ServitorInstance) member.getSummon()).getExpPenalty() : 0;
				
				final double sqLevel = member.getLevel() * member.getLevel();
				final double preCalculation = (sqLevel / sqLevelSum) * (1 - penalty);
				
				if (member.isPremium())
				{
					addexp = Math.round(member.calcStat(Stats.EXPSP_RATE, xpReward_pr * preCalculation, null, null));
					addsp = (int) member.calcStat(Stats.EXPSP_RATE, spReward_pr * preCalculation, null, null);
				}
				else
				{
					addexp = Math.round(member.calcStat(Stats.EXPSP_RATE, xpReward * preCalculation, null, null));
					addsp = (int) member.calcStat(Stats.EXPSP_RATE, spReward * preCalculation, null, null);
				}
				
				addexp = calculateExpSpPartyCutoff(member.getActingPlayer(), topLvl, addexp, addsp, useVitalityRate);
				
				if (addexp > 0)
				{
					// L2GamePlay for nevit system
					if (!member.isInsideZone(ZoneIdType.PEACE) && ((member.getLevel() - target.getLevel()) <= 9))
					{
						member.getNevitSystem().startAdventTask();
						member.getNevitSystem().checkIfMustGivePoints(addexp, target);
						
						member.updateVitalityPoints(vitalityPoints, true, false);
						PcCafePointsManager.givePcCafePoint(member, addexp);
					}
				}
			}
			else
			{
				member.addExpAndSp(0, 0);
			}
		}
	}
	
	private final long calculateExpSpPartyCutoff(L2PcInstance player, int topLvl, long addExp, int addSp, boolean vit)
	{
		long xp = addExp;
		int sp = addSp;
		if (Config.PARTY_XP_CUTOFF_METHOD.equalsIgnoreCase("highfive"))
		{
			int i = 0;
			final int lvlDiff = topLvl - player.getLevel();
			for (int[] gap : Config.PARTY_XP_CUTOFF_GAPS)
			{
				if ((lvlDiff >= gap[0]) && (lvlDiff <= gap[1]))
				{
					xp = (addExp * Config.PARTY_XP_CUTOFF_GAP_PERCENTS[i]) / 100;
					sp = (addSp * Config.PARTY_XP_CUTOFF_GAP_PERCENTS[i]) / 100;
					player.addExpAndSp(xp, sp, vit);
					break;
				}
				i++;
			}
		}
		else
		{
			player.addExpAndSp(addExp, addSp, vit);
		}
		return xp;
	}
	
	/**
	 * refresh party level
	 */
	public void recalculatePartyLevel()
	{
		int newLevel = 0;
		for (L2PcInstance member : getMembers())
		{
			if (member == null)
			{
				getMembers().remove(member);
				continue;
			}
			
			if (member.getLevel() > newLevel)
			{
				newLevel = member.getLevel();
			}
		}
		_partyLvl = newLevel;
	}
	
	private List<L2PcInstance> getValidMembers(List<L2PcInstance> members, int topLvl)
	{
		final List<L2PcInstance> validMembers = new ArrayList<>();
		
		if (Config.PARTY_XP_CUTOFF_METHOD.equalsIgnoreCase("level"))
		{
			for (L2PcInstance member : members)
			{
				if ((topLvl - member.getLevel()) <= Config.PARTY_XP_CUTOFF_LEVEL)
				{
					validMembers.add(member);
				}
			}
		}
		else if (Config.PARTY_XP_CUTOFF_METHOD.equalsIgnoreCase("percentage"))
		{
			int sqLevelSum = 0;
			for (L2PcInstance member : members)
			{
				sqLevelSum += (member.getLevel() * member.getLevel());
			}
			
			for (L2PcInstance member : members)
			{
				int sqLevel = member.getLevel() * member.getLevel();
				if ((sqLevel * 100) >= (sqLevelSum * Config.PARTY_XP_CUTOFF_PERCENT))
				{
					validMembers.add(member);
				}
			}
		}
		else if (Config.PARTY_XP_CUTOFF_METHOD.equalsIgnoreCase("auto"))
		{
			int sqLevelSum = 0;
			for (L2PcInstance member : members)
			{
				sqLevelSum += (member.getLevel() * member.getLevel());
			}
			
			int i = members.size() - 1;
			if (i < 1)
			{
				return members;
			}
			if (i >= BONUS_EXP_SP.length)
			{
				i = BONUS_EXP_SP.length - 1;
			}
			
			for (L2PcInstance member : members)
			{
				int sqLevel = member.getLevel() * member.getLevel();
				if (sqLevel >= (sqLevelSum / (members.size() * members.size())))
				{
					validMembers.add(member);
				}
			}
		}
		else if (Config.PARTY_XP_CUTOFF_METHOD.equalsIgnoreCase("highfive"))
		{
			validMembers.addAll(members);
		}
		else if (Config.PARTY_XP_CUTOFF_METHOD.equalsIgnoreCase("none"))
		{
			validMembers.addAll(members);
		}
		return validMembers;
	}
	
	private double getBaseExpSpBonus(int membersCount)
	{
		int i = membersCount - 1;
		if (i < 1)
		{
			return 1;
		}
		if (i >= BONUS_EXP_SP.length)
		{
			i = BONUS_EXP_SP.length - 1;
		}
		
		return BONUS_EXP_SP[i];
	}
	
	private double getExpBonus(int membersCount)
	{
		return (membersCount < 2) ? (getBaseExpSpBonus(membersCount)) : (getBaseExpSpBonus(membersCount) * Config.RATE_PARTY_XP);
	}
	
	private double getSpBonus(int membersCount)
	{
		return (membersCount < 2) ? (getBaseExpSpBonus(membersCount)) : (getBaseExpSpBonus(membersCount) * Config.RATE_PARTY_SP);
	}
	
	@Override
	public int getLevel()
	{
		return _partyLvl;
	}
	
	public PartyDistributionType getDistributionType()
	{
		return _distributionType != null ? _distributionType : PartyDistributionType.FINDERS_KEEPERS;
	}
	
	public boolean isInCommandChannel()
	{
		return _commandChannel != null;
	}
	
	public L2CommandChannel getCommandChannel()
	{
		return _commandChannel;
	}
	
	public void setCommandChannel(L2CommandChannel channel)
	{
		_commandChannel = channel;
	}
	
	public boolean isInDimensionalRift()
	{
		return _dr != null;
	}
	
	public void setDimensionalRift(DimensionalRift dr)
	{
		_dr = dr;
	}
	
	public DimensionalRift getDimensionalRift()
	{
		return _dr;
	}
	
	/**
	 * @return the leader of this party
	 */
	@Override
	public L2PcInstance getLeader()
	{
		return (_members != null) && !_members.isEmpty() ? _members.get(0) : null;
	}
	
	public synchronized void requestLootChange(PartyDistributionType partyDistributionType)
	{
		if (_changeRequestDistributionType != null)
		{
			return;
		}
		_changeRequestDistributionType = partyDistributionType;
		_changeDistributionTypeAnswers = new HashSet<>();
		_changeDistributionTypeRequestTask = ThreadPoolManager.getInstance().scheduleGeneral(() -> finishLootRequest(false), PARTY_DISTRIBUTION_TYPE_REQUEST_TIMEOUT.toMillis());
		
		broadcastToPartyMembers(getLeader(), new ExAskModifyPartyLooting(getLeader().getName(), partyDistributionType));
		
		final SystemMessage sm = SystemMessage.getSystemMessage(SystemMessageId.REQUESTING_APPROVAL_CHANGE_PARTY_LOOT_S1);
		sm.addSystemString(partyDistributionType.getSysStringId());
		getLeader().sendPacket(sm);
	}
	
	public synchronized void answerLootChangeRequest(L2PcInstance member, boolean answer)
	{
		if (_changeRequestDistributionType == null)
		{
			return;
		}
		
		if (_changeDistributionTypeAnswers.contains(member.getObjectId()))
		{
			return;
		}
		
		if (!answer)
		{
			finishLootRequest(false);
			return;
		}
		
		_changeDistributionTypeAnswers.add(member.getObjectId());
		if (_changeDistributionTypeAnswers.size() >= (getMemberCount() - 1))
		{
			finishLootRequest(true);
		}
	}
	
	protected synchronized void finishLootRequest(boolean success)
	{
		if (_changeRequestDistributionType == null)
		{
			return;
		}
		if (_changeDistributionTypeRequestTask != null)
		{
			_changeDistributionTypeRequestTask.cancel(false);
			_changeDistributionTypeRequestTask = null;
		}
		if (success)
		{
			broadcastPacket(new ExSetPartyLooting(1, _changeRequestDistributionType));
			_distributionType = _changeRequestDistributionType;
			SystemMessage sm = SystemMessage.getSystemMessage(SystemMessageId.PARTY_LOOT_CHANGED_S1);
			sm.addSystemString(_changeRequestDistributionType.getSysStringId());
			broadcastPacket(sm);
		}
		else
		{
			broadcastPacket(new ExSetPartyLooting(0, _distributionType));
			broadcastPacket(SystemMessage.getSystemMessage(SystemMessageId.PARTY_LOOT_CHANGE_CANCELLED));
		}
		_changeRequestDistributionType = null;
		_changeDistributionTypeAnswers = null;
	}
	
	protected class PositionBroadcast implements Runnable
	{
		@Override
		public void run()
		{
			if (_positionPacket == null)
			{
				_positionPacket = new PartyMemberPosition(L2Party.this);
			}
			else
			{
				_positionPacket.reuse(L2Party.this);
			}
			broadcastPacket(_positionPacket);
		}
	}
	
	/**
	 * @return a list of all members of this party
	 */
	@Override
	public List<L2PcInstance> getMembers()
	{
		return _members;
	}
	
	/**
	 * Check whether the leader of this party is the same as the leader of the specified party (which essentially means they're the same group).
	 * @param party the other party to check against
	 * @return {@code true} if this party equals the specified party, {@code false} otherwise
	 */
	public boolean equals(L2Party party)
	{
		return (getLeaderObjectId() == party.getLeaderObjectId());
	}
}
