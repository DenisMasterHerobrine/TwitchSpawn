package igoodie.twitchspawn.tracer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import igoodie.twitchspawn.TwitchSpawn;
import igoodie.twitchspawn.configs.Configs;
import igoodie.twitchspawn.utils.JSONHelper;
import igoodie.twitchspawn.utils.MinecraftServerUtils;
import igoodie.twitchspawn.utils.SelectionHelper;
import io.socket.client.IO;
import io.socket.client.Socket;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URISyntaxException;

public class DonationAlertsSocket {

    public static final String SOCKET_URI_FORMAT = "socket.donationalerts.ru?token=$(socketToken):3001";

    public static DonationAlertsSocket instance;

    public static boolean isRunning() {
        return instance != null;
    }

    public static void start(String socketToken) {
        if(instance != null)
            throw new IllegalStateException("DonationAlerts Socket already started!");

        if(socketToken==null || socketToken.isEmpty())
            throw new IllegalArgumentException("Invalid socket token.");

        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = false;
        opts.transports = new String[]{"websocket"};

        try {
            instance = new DonationAlertsSocket(socketToken, opts);
        } catch(InternalError e) {
            instance = null;
            throw e;
        }
    }

    public static void dispose() {
        if(instance==null)
            throw new IllegalStateException("DonationAlerts Socket is already disposed!");

        instance.socket.disconnect();
        instance = null;
        System.out.println("Instance deleted!");
    }

    public Socket socket;
    public boolean tokenVerified = false;

   private DonationAlertsSocket(String socketToken, IO.Options opts) {
        try {
           String socketUri = SOCKET_URI_FORMAT.replace("${socketToken}", socketToken);
           TwitchSpawn.LOGGER.info("Trying to connect socket: " + socketUri);
           socket = IO.socket(socketUri);
       } catch (URISyntaxException e) { // Invalid URI, We're outdated, DonationAlerts changed the URI!
            throw new InternalError();
      }

        socket.on(Socket.EVENT_CONNECT, (obj) -> {
            TwitchSpawn.LOGGER.info("Socket connection success!");
            tokenVerified = true;
        });

        socket.on(Socket.EVENT_DISCONNECT, (obj) -> {
            TwitchSpawn.LOGGER.info("Socket disconnected..");
            if(!tokenVerified) { // If token was not verified via connection
                MinecraftServerUtils.noticeChatAll("DonationAlerts Socket disconnected.. This could be caused by invalid socket token."
                        + " Please double check your token.", TextFormatting.RED);
                instance = null;
            }
        });

        socket.on("donation", (args) -> {
            JSONObject obj = (JSONObject) args[0];

            // Parse fields from received packet
            String obj_type = JSONHelper.extractString(obj, "type");
            JSONArray message = JSONHelper.extractJSONArray(obj, "message");
            TwitchSpawn.LOGGER.info("Received: " + obj_type);

            // Handle that packet accordingly
            handleMessage(obj_type, message);
        });

        socket.connect();
    }

//	public void handleDonation(JSONArray donationMessage) {
//		JsonObject rewards = Configs.json.get("rewards").getAsJsonObject();
//		JsonArray donationRewards = rewards.get("donation_rewards").getAsJsonArray();
//
//		JSONHelper.forEachJSONObject(donationMessage, (donation)->{
//			// Traverse all the rewards in reverse order, to find max
//			double amount = JSONHelper.extractDouble(donation, "amount");
//			String selectedReward = SelectionHelper.selectDonationReward(donationRewards, amount);
//
//			// If no reward fits that amount, continue to other donation messages.
//			if(selectedReward == null) return;
//
//			// Now spawn the reward!
//			dropItem(donation, selectedReward);
//		});
//	}

    public void handleMessage(String eventType, String eventFor, JSONArray message) {
        handleMessage(eventType+"|"+eventFor, message);
    }

    public void handleMessage(String eventPair, JSONArray message) {
        switch(eventPair) { // Switch type|for pair
            case "donation|donationalerts": 	handleEvent(message, "donation_rewards", "amount", "minimum_amount", "donated"); break;
            case "bits|twitch_account":			handleEvent(message, "bit_rewards", "amount", "minimum_bit", "bit-wise donated"); break;
            case "subscription|twitch_account": handleEvent(message, "sub_rewards", "months", "minimum_months", "subscribed"); break;
        }
    }

    public void handleEvent(JSONArray eventMessage, String rewardFieldName, String amountFieldName,
                            String minimumFieldName, String actionMessage) {
        JsonObject rewards = Configs.json.get("rewards").getAsJsonObject();
        JsonArray eventRewards = rewards.get(rewardFieldName).getAsJsonArray();

        // Handle each event received in the message object
        JSONHelper.forEachJSONObject(eventMessage, (donation)->{
            // Select reward with given amount
            double amount = JSONHelper.extractDouble(donation, amountFieldName);
            String selectedReward = SelectionHelper.selectReward(eventRewards, minimumFieldName, amount);

            // If no reward fits that amount, continue to other event messages.
            if(selectedReward == null) return;

            // Now spawn the reward!
            dropItem(donation, selectedReward, actionMessage);
        });
    }

    public void dropItem(JSONObject donation, String rewardName, String actionMessage) {
        String streamerNick = Configs.json.get("streamer_mc_nick").getAsString();
        String donatorNick = JSONHelper.extractString(donation, "from");

        MinecraftServer minecraftServer = FMLCommonHandler.instance().getMinecraftServerInstance();
        EntityPlayerMP streamerPlayer = minecraftServer.getPlayerList().getPlayerByUsername(streamerNick);

        if(streamerPlayer == null) { // Couldn't find the streamer on server, how is it possibru?
            TwitchSpawn.LOGGER.warn("Donation received but streamer could not be found online. Is the config file valid?");
            return;
        }

        Item item = Item.getByNameOrId(rewardName);
        ItemStack itemstack = new ItemStack(item, 1)
                .setStackDisplayName(donatorNick);
		/*TODO: NBT JSON Configs
		try {
			itemstack = new ItemStack(Items.DIAMOND_PICKAXE);
			itemstack.setTagCompound(JsonToNBT.getTagFromJson("{display:{Name:\"Sa\", Lore:\"Something\"}, Unbreakable:1}"));
		} catch (NBTException e) { MinecraftUtils.noticeChat(player, e.getMessage()); }*/

        //Drop item in front of streamer
        streamerPlayer.dropItem(itemstack, false);

        //As the server, send given player Title and Subtitle data to notice on screen
        MinecraftServerUtils.noticeScreen(streamerPlayer, donatorNick + " " + actionMessage + "!",
                donatorNick + " rewarded you with %s|" + itemstack.getItem().getUnlocalizedName(itemstack));

    }
}

