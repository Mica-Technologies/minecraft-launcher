import electronLogo from '@renderer/assets/electron.svg';

function Discover(): JSX.Element {
  return (
    <>
      <img alt="logo" className="logo" src={electronLogo} />
      <div className="creator">DISCOVER PAGE</div>
      <div className="text">
        Build a <span className="react">Minecraft</span> Launcher with&nbsp;
        <span className="ts">React and TypeScript</span>
      </div>
    </>
  );
}

export default Discover;
