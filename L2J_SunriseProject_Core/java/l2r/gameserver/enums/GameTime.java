package l2r.gameserver.enums;

/**
 * @author L2GamePlay
 */
public enum GameTime
{
	NONE(0),
	DAY(1),
	NIGHT(2);
	
	private int _id;
	
	private GameTime(int id)
	{
		_id = id;
	}
	
	public int getId()
	{
		return _id;
	}
}
